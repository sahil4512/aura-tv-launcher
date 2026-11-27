package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Landscape in illustration coordinates. Canvas rasterizes the curves at the TV's resolution. */
internal class KidsScenery(context: Context) : View(context) {
    var day = true
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shape = Path()
    private val daySky = sky(0xff80cefa, 0xffa8dcf8, 0xffffebcf)
    private val nightSky = sky(0xff101033, 0xff171840, 0xff3d4267)

    private fun sky(top: Long, middle: Long, bottom: Long) =
        LinearGradient(0f, 0f, 0f, 204f, intArrayOf(top.toInt(), middle.toInt(), bottom.toInt()),
            floatArrayOf(0f, .76f, 1f), Shader.TileMode.CLAMP)

    private fun ink(light: Long, dark: Long = light) {
        paint.color = (if (day) light else dark).toInt()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(width / 400f, height / 225f)
        paint.shader = if (day) daySky else nightSky
        c.drawRect(0f, 0f, 400f, 225f, paint)
        paint.shader = null
        c.save()
        c.translate(0f, 76f)
        if (day) {
            cloud(c, 282f, 41f, 1f)
            cloud(c, 312f, 70f, .78f)
            ink(0xffffc742)
            c.save()
            c.translate(354f, 55f)
            paint.strokeWidth = 1.7f
            paint.strokeCap = Paint.Cap.ROUND
            repeat(8) { c.drawLine(0f, -14f, 0f, -18f, paint); c.rotate(45f) }
            c.restore()
            c.drawCircle(354f, 55f, 11f, paint)
            ink(0xff6a502d)
            c.drawCircle(350.5f, 54f, .85f, paint)
            c.drawCircle(357.5f, 54f, .85f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .9f
            c.drawArc(351f, 54f, 357f, 59f, 18f, 144f, false, paint)
            paint.style = Paint.Style.FILL
        } else {
            ink(0xffffde83)
            shape.rewind()
            shape.moveTo(358f, 44f)
            shape.cubicTo(377f, 46f, 372f, 76f, 352f, 68f)
            shape.quadTo(348f, 66f, 347f, 62f)
            shape.cubicTo(358f, 68f, 366f, 54f, 358f, 44f)
            c.drawPath(shape, paint)
            star(c, 315f, 46f, 1.8f)
            star(c, 335f, 59f, 2.3f)
            star(c, 326f, 70f, 1.4f)
        }

        c.restore()

        ink(0xff79c8a5, 0xff414e8e)
        hill(c, 189f, 31f, 178f, 50f, 180f, 89f, 198f, 150f)
        ink(0xff94d6a9, 0xff32467b)
        hill(c, 215f, 84f, 177f, 111f, 190f, 161f, 208f, 206f)
        ink(0xff84cda4, 0xff46559a)
        hill(c, 225f, 194f, 186f, 233f, 189f, 301f, 211f, 352f)
        ink(0xff73bf91, 0xff194668)
        hill(c, 217f, 82f, 177f, 163f, 214f, 236f, 222f, 280f)
        ink(0xffa1ce67, 0xff24516a)
        hill(c, 264f, 250f, 224f, 328f, 183f, 400f, 201f, 405f)

        foliage(c, 378f, 199f, .48f)
        ink(0xffffebba, 0xff8d716b)
        c.drawRect(351f, 191f, 374f, 201f, paint)
        ink(0xffba3935, 0xff9d5254)
        c.drawRect(368f, 178f, 372f, 187f, paint)
        shape.rewind()
        shape.moveTo(348f, 193f); shape.lineTo(357f, 182f); shape.lineTo(369f, 182f)
        shape.lineTo(378f, 193f); shape.close()
        c.drawPath(shape, paint)
        ink(0xffeda544, 0xffffda7b)
        c.drawRect(354f, 194f, 358f, 199f, paint)
        c.drawRect(365f, 194f, 369f, 201f, paint)
        foliage(c, 0f, 225f, 1.2f)
        foliage(c, 350f, 228f, .85f)
        c.restore()
    }

    private fun hill(c: Canvas, startY: Float, x1: Float, y1: Float, x2: Float, y2: Float,
                     x3: Float, y3: Float, endX: Float) {
        shape.rewind()
        shape.moveTo(0f, startY)
        shape.cubicTo(x1, y1, x2, y2, x3, y3)
        shape.lineTo(endX, 225f); shape.lineTo(0f, 225f); shape.close()
        c.drawPath(shape, paint)
    }

    private fun foliage(c: Canvas, x: Float, y: Float, scale: Float) {
        c.save()
        c.translate(x, y); c.scale(scale, scale)
        ink(0xff46965a, 0xff123b4b)
        c.drawCircle(0f, -6f, 14f, paint)
        c.drawCircle(17f, -15f, 12f, paint)
        c.drawCircle(32f, -9f, 12f, paint)
        c.drawCircle(45f, -5f, 11f, paint)
        c.drawCircle(58f, 1f, 10f, paint)
        c.drawRect(0f, -5f, 60f, 1f, paint)
        c.restore()
    }

    private fun cloud(c: Canvas, x: Float, y: Float, scale: Float) {
        c.save()
        c.translate(x, y); c.scale(scale, scale)
        paint.color = Color.rgb(255, 250, 239)
        c.drawRoundRect(0f, 5f, 29f, 12f, 3.5f, 3.5f, paint)
        c.drawCircle(8f, 7f, 4f, paint)
        c.drawCircle(16f, 6f, 6.5f, paint)
        c.drawCircle(23f, 7f, 4.5f, paint)
        c.restore()
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        shape.rewind()
        shape.moveTo(x, y - r); shape.lineTo(x + r * .35f, y - r * .35f)
        shape.lineTo(x + r, y); shape.lineTo(x + r * .35f, y + r * .35f)
        shape.lineTo(x, y + r); shape.lineTo(x - r * .35f, y + r * .35f)
        shape.lineTo(x - r, y); shape.lineTo(x - r * .35f, y - r * .35f)
        shape.close()
        c.drawPath(shape, paint)
    }
}
