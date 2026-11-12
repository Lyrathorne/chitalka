package ru.chitalka.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chitalka.app.data.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PracticeExercisesUiTest {
    @Test fun typedAnswerAcceptsAlternativeAndSurvivesRecreation() = exercise(1,"  ФРУКТОВЫЙ САД  ")
    @Test fun clozeHidesOriginalAndChecksEnglishAnswer() = exercise(2,"ORCHARD")
    private fun exercise(mode:Int, answer:String) = runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation();val app=instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        val dao=app.repository.dao;val old=dao.currentPractice();val prefs=app.getSharedPreferences("practice_options",0)
        val oldMode=prefs.getInt("exercise",0);val oldReverse=prefs.getBoolean("reverse",false)
        val id=UUID.randomUUID().toString();val word=Word(id,"orchard",id,"сад; фруктовый сад","A quiet orchard.","e".repeat(64),"QA","{\"href\":\"chapter.xhtml\"}")
        dao.insert(word);dao.put(PracticeSession(wordIds=JSONArray(listOf(id)).toString()));prefs.edit().putInt("exercise",mode).putBoolean("reverse",false).commit()
        val device=UiDevice.getInstance(instrument)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                device.wait(Until.findObject(By.text("Практика")),10000).click()
                if(mode==2){assertTrue(device.wait(Until.hasObject(By.text("A quiet ____.")),5000));assertFalse(device.hasObject(By.text("orchard")))}
                device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000).text=answer
                scenario.onActivity { activity ->
                    activity.currentFocus?.clearFocus()
                    androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                device.clickEditorAction("Проверить ответ")
                assertTrue(device.wait(Until.hasObject(By.text("Верно")),5000))
                scenario.recreate();assertTrue(device.wait(Until.hasObject(By.text("Верно")),10000))
                feedbackCapture("exercise-$mode.png")
                device.clickEditorAction("Помню")
                withTimeout(5000){while(dao.allReviews().none{it.wordId==id})delay(100)}
                assertEquals(1,dao.allReviews().count{it.wordId==id})
            }
        } finally {
            dao.delete(word);app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReviewEvent WHERE wordId = ?",arrayOf(id))
            if(old==null)dao.clearPractice() else dao.put(old)
            prefs.edit().putInt("exercise",oldMode).putBoolean("reverse",oldReverse).commit()
        }
    }
}
