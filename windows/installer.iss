[Setup]
AppId={{9D699B76-63F5-42BF-9E9B-68324A2A12F4}
AppName=Читалка
AppVersion=1.0.0
AppPublisher=Chitalka
AppPublisherURL=https://github.com/Lyrathorne/chitalka
DefaultDirName={localappdata}\Programs\Chitalka
DefaultGroupName=Читалка
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
OutputDir=..\outputs\windows
OutputBaseFilename=chitalka-windows-1.0.0-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\Chitalka.exe
CloseApplications=yes
SetupLogging=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked

[Files]
Source: "..\outputs\windows\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\outputs\windows\dependencies\MicrosoftEdgeWebview2Setup.exe"; Flags: dontcopy

[Icons]
Name: "{group}\Читалка"; Filename: "{app}\Chitalka.exe"
Name: "{autodesktop}\Читалка"; Filename: "{app}\Chitalka.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Chitalka.exe"; Description: "Открыть Читалку"; Flags: nowait postinstall skipifsilent

[Code]
function HasWebViewInRoot(Root: Integer): Boolean;
var Names: TArrayOfString; I: Integer; DisplayName, Version: String;
begin
  Result := False;
  if RegGetSubkeyNames(Root, 'Software\Microsoft\EdgeUpdate\Clients', Names) then
    for I := 0 to GetArrayLength(Names) - 1 do
      if RegQueryStringValue(Root, 'Software\Microsoft\EdgeUpdate\Clients\' + Names[I], 'name', DisplayName) and
         (Pos('WebView2', DisplayName) > 0) and
         RegQueryStringValue(Root, 'Software\Microsoft\EdgeUpdate\Clients\' + Names[I], 'pv', Version) and
         (Version <> '') and (Version <> '0.0.0.0') then Result := True;
end;
function HasWebView(): Boolean;
begin
  Result := HasWebViewInRoot(HKCU) or HasWebViewInRoot(HKLM32) or HasWebViewInRoot(HKLM64);
end;
function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer;
begin
  Result := '';
  if not HasWebView() then begin
    ExtractTemporaryFile('MicrosoftEdgeWebview2Setup.exe');
    if not Exec(ExpandConstant('{tmp}\MicrosoftEdgeWebview2Setup.exe'), '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, Code) or not HasWebView() then
      Result := 'Не удалось установить Microsoft Edge WebView2 Runtime. Подключитесь к интернету или установите Evergreen Runtime с сайта Microsoft и повторите установку.';
  end;
end;
