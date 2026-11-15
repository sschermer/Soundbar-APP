from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_required_license_texts_are_present():
    expected = [
    "lg-bartalk_APACHE-2.0.txt",
    "lg-bartalk_NOTICE.txt",
    "customtkinter_MIT.txt",
    "pycryptodome_LICENSE.rst",
    "darkdetect_BSD-3-Clause.txt",
    "packaging_LICENSE.BSD.txt",
    "packaging_LICENSE.APACHE.txt",
    "Roboto_APACHE-2.0.txt",
    "python-3.12.10_LICENSE.txt",
    "Tcl_8.6_license.terms",
    "Tk_8.6_license.terms",
    "pyinstaller_6.22.3_COPYING.txt",
    "inno-setup_LICENSE.txt"
]
    for name in expected:
        path = ROOT / "third_party_licenses" / name
        assert path.is_file(), f"Missing: {path}"
        assert path.stat().st_size >= 100, f"License is empty: {path}"


def test_installer_bundles_license_documents():
    setup = (ROOT / "Tonmaschine.iss").read_text(encoding="utf-8")
    assert '#define AppVersion "1.0.1"' in setup
    assert 'Source: "third_party_licenses\\*"' in setup
    assert 'Source: "THIRD_PARTY_NOTICES.md"' in setup
    assert 'Source: "LICENSE"' in setup


def test_dependencies_are_pinned():
    for filename in ("requirements.txt", "requirements-build.txt"):
        lines = (ROOT / filename).read_text(encoding="utf-8").splitlines()
        assert lines
        assert all("==" in line for line in lines if line and not line.startswith("#"))
