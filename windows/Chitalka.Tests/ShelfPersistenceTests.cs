using Chitalka.Core;
using Xunit;
namespace Chitalka.Tests;
public class ShelfPersistenceTests
{
    [Fact] public void OldProfilesDefaultToEmptyShelvesAndNewSettingsPersist(){
        var root=Path.Combine(Path.GetTempPath(),"chitalka-shelves-"+Guid.NewGuid());
        try{var store=new Store(root);Assert.Empty(store.State.Shelves);Assert.False(store.State.OnlineAllowed);store.Change(s=>{s.Shelves["Романы"]=["abc"];s.OnlineAllowed=true;});var loaded=new Store(root);Assert.Equal("abc",Assert.Single(loaded.State.Shelves["Романы"]));Assert.True(loaded.State.OnlineAllowed);}
        finally{Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();if(Directory.Exists(root))Directory.Delete(root,true);}
    }
}
