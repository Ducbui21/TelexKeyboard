package com.example.telexkeyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Bàn phím Telex, bố cục và cách hoạt động giống iPhone. */
class TelexIME : InputMethodService() {

    private enum class Mode { LETTERS, SYMBOLS, MORE }
    private enum class Shift { OFF, ONCE, LOCK }

    /** Màu của một loại phím: mặt, lúc nhấn, bóng đáy, màu chữ. */
    private class Look(val face: Int, val down: Int, val shadow: Int, val text: Int)

    private class Palette(dark: Boolean) {
        val bg = if (dark) 0xFF1C1C1E.toInt() else 0xFFD1D3D9.toInt()
        val letter = if (dark) {
            Look(0xFF636366.toInt(), 0xFF8E8E93.toInt(), 0xFF1C1C1E.toInt(), Color.WHITE)
        } else {
            Look(0xFFFFFFFF.toInt(), 0xFFADB3BD.toInt(), 0xFF8C9096.toInt(), Color.BLACK)
        }
        val special = if (dark) {
            Look(0xFF3A3A3C.toInt(), 0xFF636366.toInt(), 0xFF1C1C1E.toInt(), Color.WHITE)
        } else {
            Look(0xFFADB3BD.toInt(), 0xFFFFFFFF.toInt(), 0xFF7A7F87.toInt(), Color.BLACK)
        }
        val accent = if (dark) {
            Look(0xFF0A84FF.toInt(), 0xFF5AA9FF.toInt(), 0xFF0055B3.toInt(), Color.WHITE)
        } else {
            Look(0xFF007AFF.toInt(), 0xFF005BC4.toInt(), 0xFF004C9E.toInt(), Color.WHITE)
        }
        val bubble = if (dark) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
    }

    private val raw = StringBuilder()
    private var vietnamese = true
    private var mode = Mode.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var palette = Palette(false)
    private val keyHeightDp = 44

    private var root: LinearLayout? = null
    private val letterViews = mutableListOf<Pair<Char, TextView>>()
    private var shiftKey: TextView? = null
    private var spaceKey: TextView? = null
    private var vnKey: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null
    private var popupWindow: PopupWindow? = null
    private var popupLabel: TextView? = null

    // ---------- Vòng đời ----------

    override fun onCreateInputView(): View {
        palette = Palette(isNight(resources.configuration))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(8), dp(3), dp(6))
        }
        root = container
        rebuild()
        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        raw.clear()
        mode = Mode.LETTERS
        shift = Shift.OFF
        rebuild()
        updateAutoShift()
    }

    override fun onFinishInput() {
        raw.clear()
        hidePopup()
        stopRepeat()
        super.onFinishInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = Palette(isNight(newConfig))
        rebuild()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Người dùng chạm sang chỗ khác khi đang gõ dở thì bỏ từ đang gõ
        if (raw.isNotEmpty() && candidatesEnd >= 0 &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            raw.clear()
            currentInputConnection?.finishComposingText()
        }
    }

    private fun isNight(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- Dựng giao diện ----------

    private fun rebuild() {
        val container = root ?: return
        container.removeAllViews()
        container.setBackgroundColor(palette.bg)
        letterViews.clear()
        shiftKey = null
        spaceKey = null
        vnKey = null

        when (mode) {
            Mode.LETTERS -> {
                addRow(letters("qwertyuiop"))
                addRow(listOf<View>(spacer(0.5f)) + letters("asdfghjkl") + listOf<View>(spacer(0.5f)))
                addRow(listOf<View>(shiftButton()) + letters("zxcvbnm") + listOf<View>(deleteButton()))
            }
            Mode.SYMBOLS -> {
                addRow(symbols("1234567890"))
                addRow(symbols("-/:;()\$&@\""))
                addRow(
                    listOf<View>(modeKey("#+=", 2f) { switchMode(Mode.MORE) }) +
                        symbols(".,?!'", 1.2f) +
                        listOf<View>(deleteButton(2f))
                )
            }
            Mode.MORE -> {
                addRow(symbols("[]{}#%^*+="))
                addRow(symbols("_\\|~<>€£¥•"))
                addRow(
                    listOf<View>(modeKey("123", 2f) { switchMode(Mode.SYMBOLS) }) +
                        symbols(".,?!'", 1.2f) +
                        listOf<View>(deleteButton(2f))
                )
            }
        }
        addRow(bottomRow())
        updateShiftKey()
    }

    private fun addRow(keys: List<View>) {
        val container = root ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10f
        }
        for (v in keys) row.addView(v)
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun spacer(weight: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp), weight)
    }

    private fun letters(s: String): List<View> = s.map { c ->
        val label = if (shift != Shift.OFF) c.uppercaseChar().toString() else c.toString()
        val tv = key(label, 1f, palette.letter, popupOn = true) { typeLetter(c) }
        letterViews.add(c to tv)
        tv
    }

    private fun symbols(s: String, weight: Float = 1f): List<View> =
        s.map { c -> key(c.toString(), weight, palette.letter, popupOn = true) { typeSymbol(c) } }

    private fun shiftButton(): TextView {
        val tv = key("⇧", 1.5f, palette.special, textSp = 20f) { onShiftTap() }
        shiftKey = tv
        return tv
    }

    private fun deleteButton(weight: Float = 1.5f): TextView =
        key("⌫", weight, palette.special, textSp = 20f, autoRepeat = true) { backspace() }

    private fun modeKey(label: String, weight: Float, onTap: () -> Unit): TextView =
        key(label, weight, palette.special, textSp = 16f, onTap = onTap)

    private fun bottomRow(): List<View> {
        val toggleLabel = if (mode == Mode.LETTERS) "123" else "ABC"
        val toggleTo = if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS
        return listOf<View>(
            modeKey(toggleLabel, 1.2f) { switchMode(toggleTo) },
            key("🌐", 0.9f, palette.special, textSp = 18f) { switchKeyboard() },
            vnButton(1.1f),
            spaceButton(4.6f),
            returnButton(2.2f)
        )
    }

    private fun spaceButton(weight: Float): TextView {
        val tv = key(spaceLabel(), weight, palette.letter, textSp = 15f) { onSpace() }
        spaceKey = tv
        return tv
    }

    private fun vnButton(weight: Float): TextView {
        val tv = key(langLabel(), weight, palette.special, textSp = 14f) { toggleLanguage() }
        vnKey = tv
        return tv
    }

    private fun langLabel(): String = if (vietnamese) "VI" else "EN"

    private fun spaceLabel(): String = if (vietnamese) "Tiếng Việt" else "English"

    /** Nút Return đổi nhãn và màu xanh khi ô nhập có nút Gửi/Tìm/Đi, như iPhone. */
    private fun returnButton(weight: Float): TextView {
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val (label, isAction) = when {
            (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 -> "↵" to false
            action == EditorInfo.IME_ACTION_GO -> "Đi" to true
            action == EditorInfo.IME_ACTION_SEARCH -> "Tìm" to true
            action == EditorInfo.IME_ACTION_SEND -> "Gửi" to true
            action == EditorInfo.IME_ACTION_NEXT -> "Tiếp" to true
            action == EditorInfo.IME_ACTION_DONE -> "Xong" to true
            else -> "↵" to false
        }
        val look = if (isAction) palette.accent else palette.special
        return key(label, weight, look, textSp = if (isAction) 16f else 20f) { onEnter() }
    }

    // ---------- Tạo phím ----------

    private fun key(
        label: String,
        weight: Float,
        look: Look,
        textSp: Float = 22f,
        popupOn: Boolean = false,
        autoRepeat: Boolean = false,
        onTap: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(look.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        val normal = keyBackground(look.face, look.shadow)
        background = normal
        tag = normal
        layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp), weight).apply {
            setMargins(dp(3), dp(5), dp(3), dp(5))
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = keyBackground(look.down, look.shadow)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (popupOn) showPopup(v as TextView)
                    onTap()
                    if (autoRepeat) startRepeat(onTap)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = v.tag as Drawable
                    hidePopup()
                    stopRepeat()
                }
            }
            true
        }
    }

    /** Nền phím bo tròn có bóng ở đáy, giống phím iPhone. */
    private fun keyBackground(face: Int, shadow: Int): Drawable {
        val radius = dp(6).toFloat()
        val shadowLayer = GradientDrawable().apply {
            setColor(shadow)
            cornerRadius = radius
        }
        val faceLayer = GradientDrawable().apply {
            setColor(face)
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf<Drawable>(shadowLayer, faceLayer)).apply {
            setLayerInset(1, 0, 0, 0, dp(2))
        }
    }

    /** Bong bóng chữ to hiện trên phím khi nhấn, giống iPhone. */
    private fun showPopup(anchor: TextView) {
        val label = popupLabel ?: TextView(this).also { popupLabel = it }
        label.text = anchor.text
        label.gravity = Gravity.CENTER
        label.setTextColor(palette.letter.text)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        label.background = GradientDrawable().apply {
            setColor(palette.bubble)
            cornerRadius = dp(10).toFloat()
        }
        val w = dp(56)
        val h = dp(66)
        val p = popupWindow ?: PopupWindow(label, w, h, false).also { popupWindow = it }
        val xOff = (anchor.width - w) / 2
        val yOff = -(anchor.height + h + dp(4))
        if (p.isShowing) {
            p.update(anchor, xOff, yOff, w, h)
        } else {
            p.showAsDropDown(anchor, xOff, yOff)
        }
    }

    private fun hidePopup() {
        popupWindow?.dismiss()
    }

    /** Giữ phím xóa thì xóa liên tục, như iPhone. */
    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 60L)
            }
        }
        repeater = r
        handler.postDelayed(r, 450L)
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    // ---------- Xử lý gõ ----------

    private fun typeLetter(base: Char) {
        val ic = currentInputConnection ?: return
        val ch = if (shift != Shift.OFF) base.uppercaseChar() else base
        if (shift == Shift.ONCE) setShift(Shift.OFF)
        if (vietnamese) {
            raw.append(ch)
            ic.setComposingText(TelexEngine.compose(raw.toString()), 1)
        } else {
            ic.commitText(ch.toString(), 1)
        }
    }

    private fun typeSymbol(c: Char) {
        commitWord()
        currentInputConnection?.commitText(c.toString(), 1)
    }

    private fun commitWord() {
        if (raw.isNotEmpty()) {
            raw.clear()
            currentInputConnection?.finishComposingText()
        }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (raw.isEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        raw.deleteCharAt(raw.length - 1)
        if (raw.isEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.setComposingText(TelexEngine.compose(raw.toString()), 1)
        }
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        if (raw.isEmpty()) {
            // Nhấn space hai lần sau một từ thì thành ". " như iPhone
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                updateAutoShift()
                return
            }
        } else {
            commitWord()
        }
        ic.commitText(" ", 1)
        updateAutoShift()
    }

    private fun onEnter() {
        commitWord()
        val ic = currentInputConnection ?: return
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateAutoShift()
    }

    // ---------- Shift ----------

    /** Chạm một lần: viết hoa chữ kế tiếp. Chạm hai lần nhanh: khóa viết hoa. */
    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val next = when {
            shift == Shift.LOCK -> Shift.OFF
            shift == Shift.ONCE && now - lastShiftTap < 400L -> Shift.LOCK
            shift == Shift.ONCE -> Shift.OFF
            else -> Shift.ONCE
        }
        lastShiftTap = now
        setShift(next)
    }

    private fun setShift(next: Shift) {
        shift = next
        refreshLetters()
        updateShiftKey()
    }

    private fun refreshLetters() {
        val upper = shift != Shift.OFF
        for ((c, tv) in letterViews) {
            tv.text = if (upper) c.uppercaseChar().toString() else c.toString()
        }
    }

    private fun updateShiftKey() {
        val tv = shiftKey ?: return
        val on = shift != Shift.OFF
        tv.text = if (shift == Shift.LOCK) "⇪" else "⇧"
        tv.setTextColor(if (on) Color.BLACK else palette.special.text)
        val bg = keyBackground(if (on) Color.WHITE else palette.special.face, palette.special.shadow)
        tv.background = bg
        tv.tag = bg
    }

    /** Tự viết hoa ở đầu câu, giống iPhone. */
    private fun updateAutoShift() {
        if (mode != Mode.LETTERS || shift == Shift.LOCK) return
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: 0
        val sentenceStart = raw.isEmpty() && ic.getCursorCapsMode(type) != 0
        setShift(if (sentenceStart) Shift.ONCE else Shift.OFF)
    }

    // ---------- Chuyển trang và ngôn ngữ ----------

    private fun switchMode(target: Mode) {
        commitWord()
        mode = target
        shift = Shift.OFF
        rebuild()
        if (target == Mode.LETTERS) updateAutoShift()
    }

    private fun switchKeyboard() {
        commitWord()
        switchToNextInputMethod(false)
    }

    private fun toggleLanguage() {
        commitWord()
        vietnamese = !vietnamese
        vnKey?.text = langLabel()
        spaceKey?.text = spaceLabel()
    }
}
