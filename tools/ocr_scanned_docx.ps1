param([Parameter(Mandatory=$true)][string]$Source,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.IO.Compression.FileSystem
$null=[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null=[Windows.Graphics.Imaging.BitmapDecoder,Windows.Foundation,ContentType=WindowsRuntime]
$null=[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null=[Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]
$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {$_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'} | Select-Object -First 1
function Await($operation,[Type]$type) { $task=$asTask.MakeGenericMethod($type).Invoke($null,@($operation));$task.GetAwaiter().GetResult() }
$language=New-Object Windows.Globalization.Language 'en-US'
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if(!$engine){throw 'English Windows OCR is unavailable. Install the English language OCR feature.'}
$zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Source).Path)
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('chitalka-ocr-'+[guid]::NewGuid())
[IO.Directory]::CreateDirectory($temporary)|Out-Null
try {
    function ReadXml($name){
        $entry=$zip.GetEntry($name);if(!$entry -or $entry.Length -gt 32MB){throw "Missing or oversized DOCX part: $name"}
        $settings=New-Object Xml.XmlReaderSettings
        $settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null;$settings.MaxCharactersInDocument=32MB
        $input=$entry.Open();$reader=[Xml.XmlReader]::Create($input,$settings)
        try{$doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($reader);return $doc}finally{$reader.Dispose();$input.Dispose()}
    }
    $document=ReadXml 'word/document.xml'
    $relationships=ReadXml 'word/_rels/document.xml.rels'
    $targets=@{}
    foreach($rel in $relationships.DocumentElement.ChildNodes){if($rel.TargetMode -ne 'External'){$targets[$rel.Id]=$rel.Target}}
    $images=@($document.SelectNodes("//*[local-name()='blip']") | ForEach-Object {$_.GetAttribute('embed','http://schemas.openxmlformats.org/officeDocument/2006/relationships')})
    if($images.Count -lt 1 -or $images.Count -gt 1000){throw 'OCR supports 1 to 1000 page images'}
    $pages=New-Object 'System.Collections.Generic.List[object]'
    for($i=0;$i -lt $images.Count;$i++){
        $target=$targets[$images[$i]]
        if(!$target -or $target.Contains('..') -or $target.StartsWith('/')){throw 'Unsafe or missing image target'}
        $entry=$zip.GetEntry('word/'+$target)
        if(!$entry -or $entry.Length -gt 64MB){throw 'Image missing or too large'}
        $path=Join-Path $temporary ('page'+[IO.Path]::GetExtension($target))
        $input=$entry.Open();$destination=[IO.File]::Create($path)
        try{$buffer=New-Object byte[] 32768;$total=0L;while(($n=$input.Read($buffer,0,$buffer.Length)) -gt 0){$total+=$n;if($total -gt 64MB){throw 'Image exceeds 64 MB'};$destination.Write($buffer,0,$n)}}finally{$input.Dispose();$destination.Dispose()}
        $file=Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
        $stream=Await ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        try {
            $decoder=Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            $bitmap=Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
            try {
                if($bitmap.PixelWidth -gt [Windows.Media.Ocr.OcrEngine]::MaxImageDimension -or $bitmap.PixelHeight -gt [Windows.Media.Ocr.OcrEngine]::MaxImageDimension){throw 'Image exceeds Windows OCR dimensions'}
                $result=Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
                $pages.Add(@{page=$i+1;lines=@($result.Lines | ForEach-Object {$_.Text})})
            } finally {$bitmap.Dispose()}
        } finally {$stream.Dispose()}
        Write-Output ('OCR {0}/{1}' -f ($i+1),$images.Count)
    }
    $json=ConvertTo-Json -InputObject @($pages.ToArray()) -Depth 5
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($Output),$json,(New-Object Text.UTF8Encoding $false))
} finally {
    $zip.Dispose()
    $resolved=[IO.Path]::GetFullPath($temporary)
    if(!$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'chitalka-ocr-*'){throw 'Refusing unsafe temporary cleanup'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
