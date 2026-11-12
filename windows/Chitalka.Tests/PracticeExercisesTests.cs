using Chitalka.Core;
using Xunit;
namespace Chitalka.Tests;
public class PracticeExercisesTests {
    [Theory][InlineData("  ФРУКТОВЫЙ\u00a0 САД! ","сад; фруктовый сад",true)][InlineData("don’t","don't",true)][InlineData("ＨＥＬＬＯ","hello",true)][InlineData("","сад",false)][InlineData(" ",";\n",false)][InlineData("все","всё",false)][InlineData("дом","сад; фруктовый сад",false)]
    public void AcceptedAnswers(string answer,string expected,bool correct)=>Assert.Equal(correct,PracticeExercises.Matches(answer,expected));
    [Fact]public void ClozeMasksAllOccurrencesAndRespectsWordBoundaries(){Assert.Equal("The ____ and ____.",PracticeExercises.Cloze("The orchard and ORCHARD.","orchard"));Assert.Null(PracticeExercises.Cloze("The garden.","he"));Assert.Null(PracticeExercises.Cloze("No example","orchard"));Assert.Equal("A ____ garden.",PracticeExercises.Cloze("A well-known garden.","well-known"));}
}
