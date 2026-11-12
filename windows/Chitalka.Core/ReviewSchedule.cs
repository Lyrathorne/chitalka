namespace Chitalka.Core;
// Same portable-log algorithm as Android. A day is an elapsed 24 hours, independent of DST.
public static class ReviewSchedule
{
    const long Day=86_400_000;
    public static long DueAt(IEnumerable<Review> events)
    {
        long interval=0,due=0;
        foreach(var item in events.Where(e=>e.Rating is >=0 and <=2).Distinct().OrderBy(e=>e.CreatedAt).ThenBy(e=>e.Rating)) {
            interval=item.Rating switch {0=>600_000,1=>Math.Min(180*Day,Math.Max(Day,interval*12/10)),_=>interval<Day?Day:Math.Min(180*Day,interval*25/10)};
            due=Math.Min(item.CreatedAt,long.MaxValue-interval)+interval;
        }
        return due;
    }
    public static List<Word> DueWords(IEnumerable<Word> words,IEnumerable<Review> events,long now)
    {
        var history=events.ToLookup(e=>e.WordId);
        return words.Select(w=>(word:w,due:DueAt(history[w.Id]))).Where(p=>p.due<=now)
            .OrderBy(p=>p.due).ThenBy(p=>p.word.CreatedAt).ThenBy(p=>p.word.Id,StringComparer.Ordinal).Select(p=>p.word).ToList();
    }
}
