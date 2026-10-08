/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.*
import org.junit.Test

class QueryEmotionDetectorTest {

    private val detector = RuleBasedQueryEmotionDetector()

    @Test
    fun testSadLowArousalDetected(): Unit {
        val emotion = detector.detect("今天好难过，工作上遇到了很多委屈，想哭😭")
        assertNotNull(emotion)
        assertTrue(emotion!!.valence < -0.4f)
        assertTrue(emotion.arousal < 0.45f)
        assertTrue(emotion.confidence >= 0.6f)
    }

    @Test
    fun testAngryHighArousalDetected(): Unit {
        val emotion = detector.detect("真的要被气死了！太离谱了，超级烦躁！😡")
        assertNotNull(emotion)
        assertTrue(emotion!!.valence < -0.5f)
        assertTrue(emotion.arousal > 0.6f)
        assertTrue(emotion.confidence >= 0.6f)
    }

    @Test
    fun testJoyHighArousalDetected(): Unit {
        val emotion = detector.detect("今天太棒啦！超级开心，好耶！！🎉")
        assertNotNull(emotion)
        assertTrue(emotion!!.valence > 0.5f)
        assertTrue(emotion.arousal > 0.6f)
        assertTrue(emotion.confidence >= 0.6f)
    }

    @Test
    fun testCalmLowArousalDetected(): Unit {
        val emotion = detector.detect("喝了杯热茶，感觉很舒服很安心，心情特别平静😌")
        assertNotNull(emotion)
        assertTrue("Got emotion: $emotion", emotion!!.valence > 0.3f)
        assertTrue("Got emotion: $emotion", emotion.arousal < 0.45f)
        assertTrue("Got emotion: $emotion", emotion.confidence >= 0.6f)
    }

    @Test
    fun testNeutralTextReturnsNull(): Unit {
        val emotion = detector.detect("明天下午两点去图书馆借两本书")
        assertNull(emotion)
    }

    @Test
    fun testShortTextReturnsNull(): Unit {
        val emotion = detector.detect("嗯")
        assertNull(emotion)
    }
}
