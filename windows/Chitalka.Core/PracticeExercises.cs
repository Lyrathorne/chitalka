using System.Text;
using System.Text.RegularExpressions;
namespace Chitalka.Core;
public static class PracticeExercises
{
    public static string Normalize(string answer)=>Regex.Replace(Regex.Replace(answer.Normalize(NormalizationForm.FormKC).ToLowerInvariant().Replace('’','\'').Replace('‘','\''),"[‐‑‒–—―]","-"),@"[\s\p{Z}]+"," ").Trim(' ','.','!','?');
    public static HashSet<string> Variants(string expected)=>expected.Split([';','\n']).Prepend(expected).Select(Normalize).Where(s=>s.Length>0).ToHashSet(StringComparer.Ordinal);
    public static bool Matches(string answer,string expected){var normalized=Normalize(answer);return normalized.Length>0&&Variants(expected).Contains(normalized);}
    public static string? Cloze(string context,string original){
        if(string.IsNullOrWhiteSpace(original))return null;
        var pattern=new Regex(@"(?<![\p{L}\p{N}])"+Regex.Escape(original)+@"(?![\p{L}\p{N}])",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant,TimeSpan.FromSeconds(1));
        var match=pattern.Match(context);if(!match.Success)return null;
        var start=Math.Max(0,match.Index-160);var end=Math.Min(context.Length,match.Index+match.Length+240);
        return (start>0?"…":"")+pattern.Replace(context[start..end],"____")+(end<context.Length?"…":"");
    }
}
