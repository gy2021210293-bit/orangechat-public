/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.service.AmapService
import me.rerere.rikkahub.data.service.DeviceLocationFetcher

fun createExploreNearbyTool(context: Context, settings: Settings): Tool = Tool(
    name = "explore_nearby",
    description = "按当前手机定位搜索附近真实店铺，按距离排序。评分仅是高德评分，人均和今日营业时间仅在高德提供时返回；字段为 null 就说明未知，不得猜测营业状态或关门时间。定位不可用时先问用户所在位置，不要用旧定位猜。结果不含用户坐标。",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Search keyword (e.g. restaurant, coffee, hospital, bank)")
                }
                putJsonObject("radius") {
                    put("type", "integer")
                    put("description", "Search radius in meters (default 1000, max 50000)")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "POI type code (e.g. 050000 for restaurants, 060000 for shopping, 080000 for medical)")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Maximum number of results (default 10)")
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        if (!settings.systemToolsSetting.locationExploreEnabled) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false); put("error", "周边搜索开关已关闭")
            }.toString()))
        }

        if (!hasLocationPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", "Location permission not granted") }.toString()
            ))
        }

        val apiKey = settings.systemToolsSetting.amapApiKey
        if (apiKey.isBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", "Amap API key not configured. Set it in System Tools settings.") }.toString()
            ))
        }

        try {
            val keyword = params["keyword"]?.jsonPrimitive?.content ?: ""
            val radius = params["radius"]?.jsonPrimitive?.intOrNull ?: 1000
            val type = params["type"]?.jsonPrimitive?.content ?: ""
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 25)

            // Get current location (缓存过期时会主动请求新定位)
            val fetched = DeviceLocationFetcher.fetch(context)

            if (fetched == null || !fetched.isFresh || fetched.location.time <
                System.currentTimeMillis() - 24 * 60 * 60 * 1000L) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject { put("success", false); put("error", "没有可用的今日定位，请先问用户现在在哪；不能使用旧位置搜索") }.toString()
                ))
            }
            val loc = fetched.location

            val amapService = AmapService(apiKey)
            val pois = runBlocking {
                amapService.searchNearbyPoi(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    keyword = keyword,
                    radius = radius.coerceIn(100, 50000),
                    type = type,
                    limit = limit
                )
            }

            if (pois.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("count", 0)
                        put("message", "No nearby POI found for keyword: '$keyword'")
                    }.toString()
                ))
            }

            val arr = buildJsonArray {
                pois.forEach { poi ->
                    add(buildJsonObject {
                        put("name", poi.name)
                        put("address", poi.address)
                        put("distance_m", poi.distance)
                        put("type", poi.type)
                        put("tel", poi.tel)
                        put("rating", poi.rating)
                        put("avg_cost_yuan", poi.avgCostYuan)
                        put("open_today", poi.openToday)
                    })
                }
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("count", pois.size)
                    put("source", "高德地图")
                    put("search_radius", radius.coerceIn(100, 50000))
                    put("places", arr)
                }.toString()
            ))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject { put("success", false); put("error", e.message ?: "Unknown error") }.toString()
            ))
        }
    }
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
