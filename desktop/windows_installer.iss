#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

#define AppName "小白自动剪辑"
#define AppExeName "小白自动剪辑.exe"

[Setup]
AppId={{E0D415B7-93F6-49AC-8C7D-674156E24F50}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=yuqiuyijiaren.icu
AppPublisherURL=https://yuqiuyijiaren.icu
DefaultDirName={autopf}\XiaobaiAutoEditing
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=..\release
OutputBaseFilename=XiaobaiAutoEditing-{#AppVersion}-Windows-Setup
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
CloseApplications=yes
VersionInfoVersion={#AppVersion}
VersionInfoCompany=yuqiuyijiaren.icu
VersionInfoDescription={#AppName}
VersionInfoProductName={#AppName}

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加快捷方式："; Flags: unchecked

[Files]
Source: "..\dist\小白自动剪辑\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "启动 {#AppName}"; Flags: nowait postinstall skipifsilent
