#define AppName "Tonmaschine"
#define AppVersion "1.0.1"
[Setup]
AppId={{5D73E80C-950F-4D55-9EA0-84668A79537A}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Tonmaschine
DefaultDirName={localappdata}\Programs\Tonmaschine
DefaultGroupName=Tonmaschine
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=Installer
OutputBaseFilename=Tonmaschine_Setup
SetupIconFile=Tonmaschine.ico
UninstallDisplayIcon={app}\Tonmaschine.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "dist\Tonmaschine.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "third_party_licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Tonmaschine"; Filename: "{app}\Tonmaschine.exe"
Name: "{autodesktop}\Tonmaschine"; Filename: "{app}\Tonmaschine.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Desktop-Verknuepfung erstellen"; GroupDescription: "Weitere Optionen:"; Flags: unchecked

[Run]
Filename: "{app}\Tonmaschine.exe"; Description: "Tonmaschine starten"; Flags: nowait postinstall skipifsilent

[Code]
var
  SoundbarPage: TInputQueryWizardPage;
  StoragePage: TInputOptionWizardPage;
  NasPage: TInputQueryWizardPage;

function JsonEscape(Value: String): String;
var
  I: Integer;
begin
  Result := '';
  for I := 1 to Length(Value) do
  begin
    if Value[I] = '\' then
      Result := Result + '\\'
    else if Value[I] = '"' then
      Result := Result + '\"'
    else
      Result := Result + Value[I];
  end;
end;

function ValidIPv4(Value: String): Boolean;
var
  I, Part, Num, Digits: Integer;
  Ch: Char;
begin
  Result := False;
  Part := 1;
  Num := 0;
  Digits := 0;
  for I := 1 to Length(Value) do
  begin
    Ch := Value[I];
    if (Ch >= '0') and (Ch <= '9') then
    begin
      Inc(Digits);
      if Digits > 3 then Exit;
      Num := Num * 10 + Ord(Ch) - Ord('0');
      if Num > 255 then Exit;
    end
    else if Ch = '.' then
    begin
      if (Digits = 0) or (Part >= 4) then Exit;
      Inc(Part);
      Num := 0;
      Digits := 0;
    end
    else Exit;
  end;
  Result := (Part = 4) and (Digits > 0);
end;

procedure InitializeWizard;
begin
  SoundbarPage := CreateInputQueryPage(wpSelectDir,
    'Soundbar-Verbindung', 'IP-Adresse Ihrer LG-Soundbar',
    'Geben Sie die IPv4-Adresse Ihrer Soundbar im lokalen Netzwerk ein.');
  SoundbarPage.Add('IP-Adresse:', False);

  StoragePage := CreateInputOptionPage(SoundbarPage.ID,
    'Klangprofile', 'Speicherort der Klangprofile',
    'Wo sollen Ihre Klangprofile dauerhaft gespeichert werden?', True, False);
  StoragePage.Add('Lokal auf diesem PC (empfohlen)');
  StoragePage.Add('Gemeinsamer Netzwerkordner (NAS)');
  StoragePage.Values[0] := True;

  NasPage := CreateInputQueryPage(StoragePage.ID,
    'Netzwerkspeicher', 'Pfad zum NAS-Ordner',
    'Geben Sie einen bestehenden Windows-Netzwerkordner ein, auf den Sie Schreibzugriff haben.');
  NasPage.Add('Netzwerkpfad (z. B. \\SERVER\Freigabe\Tonmaschine):', False);
end;

function ExistingConfig: Boolean;
begin
  Result := FileExists(ExpandConstant('{userappdata}\Tonmaschine\config.json'));
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  { Existing configurations belong to the user and must survive upgrades.
    They can be edited in Tonmaschine after installation. }
  Result := ExistingConfig and ((PageID = SoundbarPage.ID) or
    (PageID = StoragePage.ID) or (PageID = NasPage.ID));
  if not Result then
    Result := (PageID = NasPage.ID) and StoragePage.Values[0];
end;

function ValidUNC(Value: String): Boolean;
var
  Rest: String;
  P, I: Integer;
begin
  Result := False;
  if Copy(Value, 1, 2) <> '\\' then Exit;
  for I := 1 to Length(Value) do
    if Ord(Value[I]) < 32 then Exit;
  Rest := Copy(Value, 3, Length(Value));
  P := Pos('\', Rest);
  Result := (P > 1) and (P < Length(Rest));
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Folder: String;
begin
  Result := True;
  if CurPageID = SoundbarPage.ID then
  begin
    if not ValidIPv4(Trim(SoundbarPage.Values[0])) then
    begin
      MsgBox('Bitte eine gueltige IPv4-Adresse eingeben (z. B. 192.168.1.50).', mbError, MB_OK);
      Result := False;
    end;
  end;
  if CurPageID = NasPage.ID then
  begin
    Folder := Trim(NasPage.Values[0]);
    if not ValidUNC(Folder) then
    begin
      MsgBox('Bitte einen vollstaendigen UNC-Netzwerkpfad angeben (\\SERVER\Freigabe\Ordner).', mbError, MB_OK);
      Result := False;
    end;
    { Network access may not yet be available during setup; the application
      reports connectivity and write errors when profiles are accessed. }
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Folder, ConfigFile, Mode, Data: String;
begin
  if CurStep <> ssPostInstall then Exit;
  Folder := ExpandConstant('{userappdata}\Tonmaschine');
  if not DirExists(Folder) then
    if not ForceDirectories(Folder) then
      RaiseException('Konfigurationsordner konnte nicht angelegt werden: ' + Folder);
  ConfigFile := Folder + '\config.json';
  { Preserve original user settings on upgrades and reinstallation. }
  if FileExists(ConfigFile) then
  begin
    Log('Konfiguration existiert bereits und bleibt unveraendert: ' + ConfigFile);
    Exit;
  end;
  if StoragePage.Values[0] then Mode := 'local' else Mode := 'nas';
  Data := '{' + #13#10 +
    '  "soundbar_ip": "' + JsonEscape(Trim(SoundbarPage.Values[0])) + '",' + #13#10 +
    '  "storage_mode": "' + Mode + '",' + #13#10 +
    '  "nas_folder": "' + JsonEscape(Trim(NasPage.Values[0])) + '"' + #13#10 +
    '}' + #13#10;
  if not SaveStringToFile(ConfigFile, Data, False) then
    RaiseException('Konfiguration konnte nicht gespeichert werden: ' + ConfigFile);
end;
