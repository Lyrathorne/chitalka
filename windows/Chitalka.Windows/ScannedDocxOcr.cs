using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using Chitalka.Core;
namespace Chitalka.Windows;
public static class ScannedDocxOcr
{
    public static async Task<ImportedText> Recognize(string source,ScannedDocxException info,Action<string> progress)
    {
        var output=Path.Combine(Path.GetTempPath(),"chitalka-ocr-"+Guid.NewGuid()+".json");
        var script=Path.Combine(AppContext.BaseDirectory,"Tools","ocr_scanned_docx.ps1");
        var start=new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe")){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8};
        foreach(var arg in new[]{"-NoProfile","-ExecutionPolicy","Bypass","-File",script,"-Source",source,"-Output",output})start.ArgumentList.Add(arg);
        using var process=Process.Start(start)??throw new IOException("Не удалось запустить OCR Windows");
        using var timeout=new CancellationTokenSource(TimeSpan.FromMinutes(20));
        try {
            var errors=process.StandardError.ReadToEndAsync();
            var lines=Task.Run(async()=>{while(await process.StandardOutput.ReadLineAsync() is { } line)if(line.StartsWith("OCR "))progress("Распознаём скан DOCX: "+line[4..]);});
            await process.WaitForExitAsync(timeout.Token);await lines;var error=await errors;
            if(process.ExitCode!=0)throw new InvalidDataException("Не удалось распознать скан DOCX. Для OCR Windows нужен английский языковой пакет. "+error[..Math.Min(error.Length,250)]);
            using var json=JsonDocument.Parse(await File.ReadAllTextAsync(output));
            var chapters=json.RootElement.EnumerateArray().Select(p=>new TextChapter("Страница "+p.GetProperty("page").GetInt32(),p.GetProperty("lines").EnumerateArray().Select(l=>l.GetString()??"").Where(s=>!string.IsNullOrWhiteSpace(s)).ToList())).ToList();
            var count=chapters.Sum(c=>c.Paragraphs.Sum(p=>p.Length));if(count<10||count>DocumentImporter.MaxText)throw new InvalidDataException("OCR не нашёл доступного текста или книга слишком большая");
            return new(info.BookTitle,info.BookAuthor,"DOCX OCR",chapters);
        } catch(OperationCanceledException){if(!process.HasExited)process.Kill(true);throw new IOException("Распознавание заняло слишком много времени");}
        finally{if(File.Exists(output))File.Delete(output);}
    }
}
