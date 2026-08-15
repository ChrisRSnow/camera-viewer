"""Main window for the desktop viewer client.

Mirrors the Android app's viewer role: pick/discover a sender, watch its
live feed via the video relay, listen for alerts in the background (with a
desktop notification + auto-switch to the alerting camera, same as the
Android app's alert-routed connect), and browse snapshots on demand.
"""

from __future__ import annotations

import io

from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

try:
    from plyer import notification as desktop_notification
except ImportError:  # pragma: no cover - plyer is an optional runtime dep
    desktop_notification = None

import config
import snapshot_client
import tailscale_discovery
from alert_server import AlertServer
from relay_client import MjpegStream


class _VideoBridge(QObject):
    """MjpegStream's callbacks run on its own background thread; Qt widgets
    can only be touched from the GUI thread. Routing them through Signals
    (created on this QObject, which lives on the GUI thread) lets Qt
    marshal the emit() calls across threads safely via its normal queued
    connection mechanism — no manual locking needed."""

    frame_received = Signal(bytes)
    status_changed = Signal(str)


class _AlertBridge(QObject):
    alert_received = Signal(str, str)  # label, ip


class SettingsDialog(QDialog):
    def __init__(self, parent, cfg: dict):
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.cfg = cfg

        self.token_edit = QLineEdit(cfg.get("tailscale_api_token", ""))
        self.token_edit.setEchoMode(QLineEdit.EchoMode.Password)
        self.label_edit = QLineEdit()
        self.ip_edit = QLineEdit()
        self.ip_edit.setPlaceholderText("100.x.x.x")

        add_camera_row = QHBoxLayout()
        add_camera_row.addWidget(self.label_edit)
        add_camera_row.addWidget(self.ip_edit)
        add_btn = QPushButton("Add")
        add_btn.clicked.connect(self._add_camera)
        add_camera_row.addWidget(add_btn)

        self.camera_list = QListWidget()
        self._refresh_camera_list()
        remove_btn = QPushButton("Remove selected")
        remove_btn.clicked.connect(self._remove_selected)

        form = QFormLayout()
        form.addRow("Tailscale API token:", self.token_edit)

        layout = QVBoxLayout(self)
        layout.addLayout(form)
        layout.addWidget(QLabel("Known cameras (label, Tailscale IP):"))
        layout.addLayout(add_camera_row)
        layout.addWidget(self.camera_list)
        layout.addWidget(remove_btn)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _refresh_camera_list(self) -> None:
        self.camera_list.clear()
        for cam in self.cfg.get("known_cameras", []):
            self.camera_list.addItem(f"{cam['label']} — {cam['ip']}")

    def _add_camera(self) -> None:
        label = self.label_edit.text().strip()
        ip = self.ip_edit.text().strip()
        if not label or not ip:
            return
        self.cfg.setdefault("known_cameras", []).append({"label": label, "ip": ip})
        self.label_edit.clear()
        self.ip_edit.clear()
        self._refresh_camera_list()

    def _remove_selected(self) -> None:
        row = self.camera_list.currentRow()
        if row < 0:
            return
        del self.cfg["known_cameras"][row]
        self._refresh_camera_list()

    def accept(self) -> None:
        self.cfg["tailscale_api_token"] = self.token_edit.text().strip()
        config.save(self.cfg)
        super().accept()


class SnapshotsDialog(QDialog):
    def __init__(self, parent, ip: str):
        super().__init__(parent)
        self.ip = ip
        self.setWindowTitle(f"Snapshots — {ip}")
        self.resize(500, 400)

        self.list_widget = QListWidget()
        self.list_widget.itemDoubleClicked.connect(self._view_selected)
        delete_btn = QPushButton("Delete selected")
        delete_btn.clicked.connect(self._delete_selected)
        refresh_btn = QPushButton("Refresh")
        refresh_btn.clicked.connect(self._load)

        layout = QVBoxLayout(self)
        layout.addWidget(self.list_widget)
        row = QHBoxLayout()
        row.addWidget(refresh_btn)
        row.addWidget(delete_btn)
        layout.addLayout(row)

        self._load()

    def _load(self) -> None:
        self.list_widget.clear()
        try:
            snaps = snapshot_client.list_snapshots(self.ip)
        except Exception as e:  # noqa: BLE001 - surfaced to the user, not a crash
            QMessageBox.warning(self, "Snapshots", f"Could not list snapshots: {e}")
            return
        for snap in snaps:
            item = QListWidgetItem(snap["filename"])
            item.setData(Qt.ItemDataRole.UserRole, snap["filename"])
            self.list_widget.addItem(item)

    def _view_selected(self, item: QListWidgetItem) -> None:
        filename = item.data(Qt.ItemDataRole.UserRole)
        try:
            data = snapshot_client.fetch_image(self.ip, filename)
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "Snapshots", f"Could not fetch image: {e}")
            return
        preview = QDialog(self)
        preview.setWindowTitle(filename)
        pixmap = QPixmap()
        pixmap.loadFromData(data)
        label = QLabel()
        label.setPixmap(pixmap.scaled(600, 600, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
        layout = QVBoxLayout(preview)
        layout.addWidget(label)
        preview.exec()

    def _delete_selected(self) -> None:
        item = self.list_widget.currentItem()
        if item is None:
            return
        filename = item.data(Qt.ItemDataRole.UserRole)
        if snapshot_client.delete_snapshot(self.ip, filename):
            self._load()
        else:
            QMessageBox.warning(self, "Snapshots", "Delete failed.")


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Camera Viewer")
        self.resize(720, 560)

        self.cfg = config.load()
        self._stream: MjpegStream | None = None
        self._video_bridge = _VideoBridge()
        self._video_bridge.frame_received.connect(self._on_frame)
        self._video_bridge.status_changed.connect(self._on_status)

        self._alert_bridge = _AlertBridge()
        self._alert_bridge.alert_received.connect(self._on_alert)
        self._alert_server = AlertServer(on_alert=self._alert_bridge.alert_received.emit)

        self.camera_combo = QComboBox()
        self.camera_combo.setEditable(False)
        self._refresh_camera_combo()

        scan_btn = QPushButton("Scan tailnet")
        scan_btn.clicked.connect(self._scan_tailnet)
        settings_btn = QPushButton("Settings")
        settings_btn.clicked.connect(self._open_settings)
        watch_btn = QPushButton("Watch")
        watch_btn.clicked.connect(self._watch_selected)
        snapshots_btn = QPushButton("View Snapshots")
        snapshots_btn.clicked.connect(self._open_snapshots)

        top_row = QHBoxLayout()
        top_row.addWidget(self.camera_combo, stretch=1)
        top_row.addWidget(watch_btn)
        top_row.addWidget(scan_btn)
        top_row.addWidget(snapshots_btn)
        top_row.addWidget(settings_btn)

        self.video_label = QLabel("No camera selected")
        self.video_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.video_label.setMinimumSize(480, 360)
        self.video_label.setStyleSheet("background-color: black; color: white;")

        self.status_label = QLabel("Idle")
        self.listening_label = QLabel("Listening: starting…")

        bottom_row = QHBoxLayout()
        bottom_row.addWidget(self.status_label, stretch=1)
        bottom_row.addWidget(self.listening_label)

        central = QWidget()
        layout = QVBoxLayout(central)
        layout.addLayout(top_row)
        layout.addWidget(self.video_label, stretch=1)
        layout.addLayout(bottom_row)
        self.setCentralWidget(central)

        self._start_alert_listener()

    # -- alert listening -------------------------------------------------

    def _start_alert_listener(self) -> None:
        try:
            self._alert_server.start()
            self.listening_label.setText("Listening: yes")
        except OSError as e:
            self.listening_label.setText(f"Listening: failed ({e})")

    def _on_alert(self, label: str, ip: str) -> None:
        if desktop_notification is not None:
            try:
                desktop_notification.notify(title=f"Person detected — {label}", message="Tap the app to view", timeout=10)
            except Exception:  # noqa: BLE001 - notification backend availability varies by OS/DE
                pass
        if ip:
            # Same reasoning as the Android app's alert-routed connect:
            # switch straight to the alerting camera rather than requiring
            # the user to notice and pick it manually.
            self._ensure_known_camera(label, ip)
            self._watch(ip)

    # -- camera list / discovery ------------------------------------------

    def _refresh_camera_combo(self) -> None:
        self.camera_combo.clear()
        for cam in self.cfg.get("known_cameras", []):
            self.camera_combo.addItem(f"{cam['label']} — {cam['ip']}", userData=cam["ip"])

    def _ensure_known_camera(self, label: str, ip: str) -> None:
        cams = self.cfg.setdefault("known_cameras", [])
        if not any(c["ip"] == ip for c in cams):
            cams.append({"label": label or ip, "ip": ip})
            config.save(self.cfg)
            self._refresh_camera_combo()

    def _scan_tailnet(self) -> None:
        token = self.cfg.get("tailscale_api_token", "")
        if not token:
            QMessageBox.information(self, "Scan tailnet", "Set a Tailscale API token in Settings first.")
            return
        self.status_label.setText("Scanning tailnet…")
        try:
            found = tailscale_discovery.scan_for_cameras(token)
        except tailscale_discovery.TailscaleApiError as e:
            QMessageBox.warning(self, "Scan tailnet", str(e))
            self.status_label.setText("Scan failed")
            return
        for peer in found:
            self._ensure_known_camera(peer["hostname"], peer["ip"])
        self.status_label.setText(f"Scan found {len(found)} camera(s)")

    def _open_settings(self) -> None:
        dialog = SettingsDialog(self, self.cfg)
        if dialog.exec():
            self._refresh_camera_combo()

    # -- video -------------------------------------------------------------

    def _watch_selected(self) -> None:
        ip = self.camera_combo.currentData()
        if ip:
            self._watch(ip)

    def _watch(self, ip: str) -> None:
        if self._stream is not None:
            self._stream.stop()
        self.cfg["last_camera_ip"] = ip
        config.save(self.cfg)
        self._stream = MjpegStream(ip, on_frame=self._video_bridge.frame_received.emit, on_status=self._video_bridge.status_changed.emit)
        self._stream.start()
        # Keep the combo box in sync when an alert switches the camera out
        # from under a manual selection.
        idx = self.camera_combo.findData(ip)
        if idx >= 0:
            self.camera_combo.setCurrentIndex(idx)

    def _on_frame(self, frame: bytes) -> None:
        pixmap = QPixmap()
        if pixmap.loadFromData(frame):
            self.video_label.setPixmap(
                pixmap.scaled(
                    self.video_label.size(),
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                ),
            )

    def _on_status(self, text: str) -> None:
        self.status_label.setText(text)

    # -- snapshots -----------------------------------------------------

    def _open_snapshots(self) -> None:
        ip = self.camera_combo.currentData() or self.cfg.get("last_camera_ip")
        if not ip:
            QMessageBox.information(self, "Snapshots", "No camera known yet — watch one first.")
            return
        SnapshotsDialog(self, ip).exec()

    def closeEvent(self, event) -> None:  # noqa: N802 - Qt override
        if self._stream is not None:
            self._stream.stop()
        self._alert_server.stop()
        super().closeEvent(event)
