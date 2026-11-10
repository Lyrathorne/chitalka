package ru.chitalka.app

import androidx.test.uiautomator.*

fun UiDevice.searchField(): UiObject2 {
    findObject(By.clazz("android.widget.EditText"))?.let { return it }
    wait(Until.findObject(By.desc("Меню библиотеки")), 5000).click()
    wait(Until.findObject(By.text("Поиск")), 5000).click()
    return wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
}
