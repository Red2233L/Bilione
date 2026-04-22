// 文件路径: data/repository/VideoRepository.kt
package com.android.bilione

import android.util.Log
import com.android.bilione.network.NetworkModule
import com.android.bilione.network.WbiUtils
//import com.android.purebilibili.core.network.NetworkModule
//import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.TreeMap // 🔥 引入 TreeMap 用于参数排序
import java.util.zip.Inflater

object VideoRepository {
    private val api = NetworkModule.api

    private val QUALITY_CHAIN = listOf(120, 116, 112, 80, 64, 32, 16)

    // 1. 首页推荐
    suspend fun getHomeVideos(idx: Int = 0): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val navResp = api.getNavInfo()
            val wbiImg = navResp.data?.wbi_img ?: throw Exception("无法获取 Key")
            val imgKey = wbiImg.img_url.substringAfterLast("/").substringBefore(".")
            val subKey = wbiImg.sub_url.substringAfterLast("/").substringBefore(".")

            val params = mapOf(
                "ps" to "10", "fresh_type" to "3", "fresh_idx" to idx.toString(),
                "feed_version" to System.currentTimeMillis().toString(), "y_num" to idx.toString()
            )
            val signedParams = WbiUtils.sign(params, imgKey, subKey)
            val feedResp = api.getRecommendParams(signedParams)
            val list = feedResp.data?.item?.map { it.toVideoItem() }?.filter { it.bvid.isNotEmpty() } ?: emptyList()
            Result.success(list)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun getNavInfo(): Result<NavData> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getNavInfo()
            if (resp.code == 0 && resp.data != null) {
                Result.success(resp.data)
            } else {
                if (resp.code == -101) {
                    Result.success(NavData(isLogin = false))
                } else {
                    Result.failure(Exception("错误码: ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideoDetails(bvid: String): Result<Pair<ViewInfo, PlayUrlData>> = withContext(Dispatchers.IO) {
        try {
            val viewResp = api.getVideoInfo(bvid)
            val info = viewResp.data ?: throw Exception("视频详情为空: ${viewResp.code}")
            val cid = info.cid
            if (cid == 0L) throw Exception("CID 获取失败")

            val isLogin = !TokenManager.sessDataCache.isNullOrEmpty()
            val startQuality = if (isLogin) 120 else 80

            val playData = fetchPlayUrlRecursive(bvid, cid, startQuality)
                ?: throw Exception("无法获取任何画质的播放地址")

            // 🔥 支持 DASH 和 durl 两种格式
            val hasDash = !playData.dash?.video.isNullOrEmpty()
            val hasDurl = !playData.durl.isNullOrEmpty()
            if (!hasDash && !hasDurl) throw Exception("播放地址解析失败 (无 dash/durl)")

            Result.success(Pair(info, playData))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    // 🔥🔥 [新增] WBI Key 缓存，防止递归请求时频繁访问 /nav 导致 412 风控
    private var wbiKeysCache: Pair<String, String>? = null
    private var wbiKeysTimestamp: Long = 0
    private const val WBI_CACHE_DURATION = 1000 * 60 * 60 // 1小时缓存

    private suspend fun getWbiKeys(): Pair<String, String> {
        val currentCheck = System.currentTimeMillis()
        if (wbiKeysCache != null && (currentCheck - wbiKeysTimestamp < WBI_CACHE_DURATION)) {
            return wbiKeysCache!!
        }

        // 🔥 带重试的 WBI Key 获取
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) {
                    Log.d("VideoRepo", "🔥 getWbiKeys retry attempt ${attempt + 1}")
                    delay(500L * attempt)
                }
                
                val navResp = api.getNavInfo()
                val wbiImg = navResp.data?.wbi_img
                
                if (wbiImg != null) {
                    val imgKey = wbiImg.img_url.substringAfterLast("/").substringBefore(".")
                    val subKey = wbiImg.sub_url.substringAfterLast("/").substringBefore(".")
                    
                    wbiKeysCache = Pair(imgKey, subKey)
                    wbiKeysTimestamp = currentCheck
                    Log.d("VideoRepo", "🔥 WBI Keys obtained successfully")
                    return wbiKeysCache!!
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("VideoRepo", "getWbiKeys attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        
        throw Exception("Wbi Keys Error after 3 attempts: ${lastError?.message}")
    }

    suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int): PlayUrlData? = withContext(Dispatchers.IO) {
        // 🔥 简化策略：单次请求，如果 412 则等待 2s 后重试一次
        var result = fetchPlayUrlWithWbi(bvid, cid, qn)
        if (result == null) {
            Log.d("VideoRepo", "🔥 First attempt failed, waiting 2s before retry...")
            delay(2000)
            result = fetchPlayUrlWithWbi(bvid, cid, qn)
        }
        result
    }

    // 🔥🔥 [稳定版核心修复] 获取评论列表
    suspend fun getComments(aid: Long, page: Int, ps: Int = 20): Result<ReplyData> = withContext(Dispatchers.IO) {
        try {
            // 🔥 使用缓存 Keys
            val (imgKey, subKey) = getWbiKeys()

            // 🔥 使用 TreeMap 保证签名顺序绝对正确
            val params = TreeMap<String, String>()
            params["oid"] = aid.toString()
            params["type"] = "1"     // 1: 视频评论区
            params["mode"] = "3"     // 3: 按热度排序
            params["next"] = page.toString()
            params["ps"] = ps.toString()

            val signedParams = WbiUtils.sign(params, imgKey, subKey)
            val response = api.getReplyList(signedParams)

            if (response.code == 0) {
                Result.success(response.data ?: ReplyData())
            } else {
                Result.failure(Exception("B站接口错误: ${response.code} - ${response.message}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    // 🔥🔥 [新增] 获取二级评论 (楼中楼)
    suspend fun getSubComments(aid: Long, rootId: Long, page: Int, ps: Int = 20): Result<ReplyData> = withContext(Dispatchers.IO) {
        try {
            // 注意：需要在 ApiClient.kt 中定义 getReplyReply 接口
            val response = api.getReplyReply(
                oid = aid,
                root = rootId,
                pn = page,
                ps = ps
            )
            if (response.code == 0) {
                Result.success(response.data ?: ReplyData())
            } else {
                Result.failure(Exception("接口错误: ${response.code}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun getEmoteMap(): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        map["[doge]"] = "http://i0.hdslb.com/bfs/emote/6f8743c3c13009f4705307b2750e32f5068225e3.png"
        map["[笑哭]"] = "http://i0.hdslb.com/bfs/emote/500b63b2f293309a909403a746566fdd6104d498.png"
        map["[妙啊]"] = "http://i0.hdslb.com/bfs/emote/03c39c8eb009f63568971032b49c716259c72441.png"
        try {
            val response = api.getEmotes()
            response.data?.packages?.forEach { pkg ->
                pkg.emote?.forEach { emote -> map[emote.text] = emote.url }
            }
        } catch (e: Exception) { e.printStackTrace() }
        map
    }

    // 🔥🔥 [优化] 核心播放地址获取逻辑，带指数退避重试
    private suspend fun fetchPlayUrlRecursive(bvid: String, cid: Long, targetQn: Int): PlayUrlData? {
        // 🔥 策略改变：直接尝试获取，API 会自动返回用户可用的最高画质
        // 不再强制递归降级，减少请求次数
        
        var lastError: Exception? = null
        var retryDelays = listOf(0L, 1000L, 2000L) // 立即、1秒、2秒
        
        for ((attempt, delay) in retryDelays.withIndex()) {
            if (delay > 0) {
                Log.d("VideoRepo", "🔥 Retry attempt ${attempt + 1} after ${delay}ms...")
                delay(delay)
            }
            
            try {
                val data = fetchPlayUrlWithWbiInternal(bvid, cid, targetQn)
                if (data != null && (!data.durl.isNullOrEmpty() || !data.dash?.video.isNullOrEmpty())) {
                    Log.d("VideoRepo", "🔥 Got valid PlayUrl on attempt ${attempt + 1}: requested=$targetQn, actual=${data.quality}")
                    return data
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("VideoRepo", "fetchPlayUrl attempt ${attempt + 1} failed: ${e.message}")
                
                // 🔥 如果是 WBI Key 错误，尝试刷新缓存
                if (e.message?.contains("Wbi Keys Error") == true) {
                    wbiKeysCache = null
                    wbiKeysTimestamp = 0
                }
            }
        }
        
        // 🔥 所有重试都失败后，尝试降级画质（只降一级）
        if (targetQn > 64) {
            Log.d("VideoRepo", "🔥 All retries failed, trying lower quality...")
            delay(500)
            return fetchPlayUrlRecursive(bvid, cid, 64) // 降级到 720P
        }
        
        Log.e("VideoRepo", "🔥 fetchPlayUrlRecursive completely failed for bvid=$bvid")
        return null
    }

    // 🔥 内部方法：单次请求播放地址
    private suspend fun fetchPlayUrlWithWbiInternal(bvid: String, cid: Long, qn: Int): PlayUrlData? {
        Log.d("VideoRepo", "fetchPlayUrlWithWbiInternal: bvid=$bvid, cid=$cid, qn=$qn")
        
        // 🔥 使用缓存的 Keys
        val (imgKey, subKey) = getWbiKeys()
        
        val params = mapOf(
            "bvid" to bvid, "cid" to cid.toString(), "qn" to qn.toString(),
            "fnval" to "16", "fnver" to "0", "fourk" to "1", "platform" to "html5", "high_quality" to "1"
        )
        val signedParams = WbiUtils.sign(params, imgKey, subKey)
        val response = api.getPlayUrl(signedParams)
        
        Log.d("VideoRepo", "🔥 PlayUrl response: code=${response.code}, requestedQn=$qn, returnedQuality=${response.data?.quality}")
        Log.d("VideoRepo", "🔥 accept_quality=${response.data?.accept_quality}, accept_description=${response.data?.accept_description}")
        
        if (response.code == 0) return response.data
        
        // 🔥 API 返回错误码
        Log.e("VideoRepo", "🔥 PlayUrl API error: code=${response.code}, message=${response.message}")
        return null
    }

    // 🔥🔥 [废弃旧方法，保留兼容性] 原 fetchPlayUrlWithWbi
    private suspend fun fetchPlayUrlWithWbi(bvid: String, cid: Long, qn: Int): PlayUrlData? {
        try {
            return fetchPlayUrlWithWbiInternal(bvid, cid, qn)
        } catch (e: HttpException) {
            Log.e("VideoRepo", "HttpException: ${e.code()}")
            if (e.code() in listOf(402, 403, 404, 412)) return null
            throw e
        } catch (e: Exception) { 
            Log.e("VideoRepo", "Exception: ${e.message}")
            return null 
        }
    }

    suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = withContext(Dispatchers.IO) {
        try { api.getRelatedVideos(bvid).data ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    suspend fun getDanmakuRawData(cid: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val responseBody = api.getDanmakuXml(cid)
            val bytes = responseBody.bytes() // 下载所有数据

            if (bytes.isEmpty()) return@withContext null

            // 检查首字节 判断是否压缩
            // XML 以 '<' 开头 (0x3C)
            if (bytes[0] == 0x3C.toByte()) {
                return@withContext bytes
            }

            // 尝试 Deflate 解压
            try {
                val inflater = Inflater(true) // nowrap=true
                inflater.setInput(bytes)
                val buffer = ByteArray(1024 * 1024 * 4) // max 4MB buffer? 自动扩容较麻烦，先用 simple approach
                val outputStream = ByteArrayOutputStream(bytes.size * 3)
                val tempBuffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(tempBuffer)
                    if (count == 0) {
                         if (inflater.needsInput()) break
                         if (inflater.needsDictionary()) break
                    }
                    outputStream.write(tempBuffer, 0, count)
                }
                inflater.end()
                return@withContext outputStream.toByteArray()
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果解压失败，返回原始数据（万一是普通 XML 但只有空格在前？）
                return@withContext bytes
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}