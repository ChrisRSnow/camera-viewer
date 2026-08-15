"""Entry point for the desktop viewer client (Windows/Linux) — see README.md
for setup. Same protocol as the Android app's viewer role: Tailscale
discovery, the video relay, alert listening, and snapshot browsing."""

import sys

from PySide6.QtWidgets import QApplication

from gui import MainWindow


def main() -> int:
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
