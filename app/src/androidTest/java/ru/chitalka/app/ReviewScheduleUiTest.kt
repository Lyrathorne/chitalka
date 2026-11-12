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
class ReviewScheduleUiTest {
    @Test fun reverseCardHidesEnglishAnswerAndSchedulesNextReview() = runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ChitalkaApp
        assertTrue(app.packageName.endsWith(".mvpcheck"))
        val dao=app.repository.dao
        val old=dao.currentPractice()
        val prefs=app.getSharedPreferences("practice_options",0);val reverse=prefs.getBoolean("reverse",false)
        val id=UUID.randomUUID().toString()
        val word=Word(id,"orchard",id,"фруктовый сад","A quiet orchard.","d".repeat(64),"QA","{\"href\":\"chapter.xhtml\"}")
        dao.insert(word);dao.put(PracticeSession(wordIds=JSONArray(listOf(id)).toString()));prefs.edit().putBoolean("reverse",true).commit()
        val device=UiDevice.getInstance(instrument)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                device.wait(Until.findObject(By.text("Практика")),10000).click()
                assertTrue(device.wait(Until.hasObject(By.text(word.translation)),5000));assertFalse(device.hasObject(By.text(word.original)))
                device.findObject(By.text("Показать ответ")).click()
                assertTrue(device.wait(Until.hasObject(By.text(word.original)),5000))
                feedbackCapture("reverse-card.png")
                device.findObject(By.text("Помню")).click()
                withTimeout(5000){while(dao.allReviews().none{it.wordId==id})delay(100)}
                assertTrue(ReviewSchedule.dueAt(dao.allReviews().filter{it.wordId==id})>System.currentTimeMillis())
            }
        } finally {
            dao.delete(word);app.repository.database.openHelper.writableDatabase.execSQL("DELETE FROM ReviewEvent WHERE wordId = ?",arrayOf(id))
            if(old==null)dao.clearPractice() else dao.put(old)
            prefs.edit().putBoolean("reverse",reverse).commit()
        }
    }
}
