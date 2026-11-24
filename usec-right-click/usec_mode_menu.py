from pathlib import Path

import gi

gi.require_version('Nautilus', '3.0')
gi.require_version('Gtk', '3.0')
gi.require_version('Notify', '0.7')
from gi.repository import Gio, GLib, GObject, Gtk, Nautilus, Notify


HELPER = Path(GLib.get_user_data_dir()) / 'usec-right-click' / 'usec_mode_switch.py'


class UsecModeMenu(GObject.GObject, Nautilus.MenuProvider):
    def get_background_items(self, window, _folder):
        items = []
        for target, label in (('plaintext', '切换为明文'), ('ciphertext', '切换为密文')):
            item = Nautilus.MenuItem(name='UsecModeMenu::' + target, label=label)
            item.connect('activate', self.activate, target, window)
            items.append(item)
        return items

    def activate(self, _item, target, window):
        try:
            process = Gio.Subprocess.new(
                ['/usr/bin/python3', str(HELPER), target],
                Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE,
            )
        except GLib.Error as error:
            self.show_error(window, error.message)
            return
        process.communicate_utf8_async(None, None, self.finished, window)

    def finished(self, process, result, window):
        try:
            _, output, errors = process.communicate_utf8_finish(result)
            if process.get_successful():
                Notify.init('USEC 模式切换')
                Notify.Notification.new('USEC 模式切换', output.strip(), None).show()
            else:
                self.show_error(window, errors.strip())
        except GLib.Error as error:
            self.show_error(window, error.message)

    def show_error(self, window, message):
        dialog = Gtk.MessageDialog(
            transient_for=window, message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.CLOSE, text='USEC', secondary_text=message,
        )
        dialog.connect('response', lambda widget, _response: widget.destroy())
        dialog.show()
