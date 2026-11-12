using Chitalka.Core;
using Xunit;
namespace Chitalka.Tests;
public class ReviewScheduleTests
{
    const long Day=86_400_000;
    [Fact] public void NewWordsAndLapses(){Assert.Equal(0,ReviewSchedule.DueAt([]));Assert.Equal(600_100,ReviewSchedule.DueAt([new("w",0,100)]));}
    [Fact] public void GrowthDuplicatesSortingAndReset(){var a=new Review("w",2,100);var b=new Review("w",2,Day+100);Assert.Equal(3*Day+Day/2+100,ReviewSchedule.DueAt([b,a,a]));Assert.Equal(4*Day+600_000,ReviewSchedule.DueAt([b,a,new("w",0,4*Day)]));}
    [Fact] public void DueBoundaryAndOverdueWords(){var word=new Word("w","word","word","слово","","hash","Book","{}","QA",0);Assert.Empty(ReviewSchedule.DueWords([word],[new("w",2,100)],Day+99));Assert.Single(ReviewSchedule.DueWords([word],[new("w",2,100)],Day+100));Assert.Single(ReviewSchedule.DueWords([word],[new("w",2,100)],30*Day));}
}
