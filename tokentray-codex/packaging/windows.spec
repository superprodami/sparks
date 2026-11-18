from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

root = Path(SPECPATH).parent
analysis = Analysis(
    [str(root / "packaging" / "console.py"), str(root / "packaging" / "gui.py")],
    pathex=[str(root / "src")],
    datas=collect_data_files("tokentray"),
    hiddenimports=collect_submodules("tokentray") + ["keyring.backends.Windows"],
)
# Qt uses Windows' system ICU. A build-host PATH entry may supply an
# incompatible ICU DLL whose exports have version suffixes.
analysis.binaries = [
    binary for binary in analysis.binaries
    if Path(binary[0]).name.lower() not in {"icuuc.dll", "icudt78.dll"}
]
archive = PYZ(analysis.pure)
console = EXE(
    archive,
    [script for script in analysis.scripts if script[0] != "gui"],
    exclude_binaries=True,
    name="tokentray",
    console=True,
    icon=str(root / "packaging" / "tokentray.ico"),
)
gui = EXE(
    archive,
    [script for script in analysis.scripts if script[0] != "console"],
    exclude_binaries=True,
    name="tokentray-gui",
    console=False,
    icon=str(root / "packaging" / "tokentray.ico"),
)
bundle = COLLECT(
    console,
    gui,
    analysis.binaries,
    analysis.datas,
    name="tokentray",
)
