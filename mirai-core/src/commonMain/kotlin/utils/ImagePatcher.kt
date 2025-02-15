/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

package net.mamoe.mirai.internal.utils

import net.mamoe.mirai.internal.contact.GroupImpl
import net.mamoe.mirai.internal.message.OfflineGroupImage
import net.mamoe.mirai.internal.network.component.ComponentKey
import net.mamoe.mirai.internal.network.protocol.packet.chat.image.ImgStore
import net.mamoe.mirai.internal.network.protocol.packet.sendAndExpect
import net.mamoe.mirai.message.data.FriendImage
import net.mamoe.mirai.message.data.md5
import net.mamoe.mirai.utils.ResourceAccessLock
import net.mamoe.mirai.utils.UnsafeMutableNonNullProperty
import net.mamoe.mirai.utils.currentTimeMillis
import net.mamoe.mirai.utils.unsafeMutableNonNullPropertyOf

internal open class ImagePatcher {
    companion object : ComponentKey<ImagePatcher> {
        inline fun <T> ImageCache.withCache(action: (ImageCache) -> T): T {
            return try {
                action(this)
            } finally {
                this.accessLock.release()
            }
        }
    }

    data class ImageCache(
        var updateTime: Long = 0,
        val id: UnsafeMutableNonNullProperty<String> = unsafeMutableNonNullPropertyOf(),
        // OGI: OfflineGroupImage
        val cacheOGI: UnsafeMutableNonNullProperty<OfflineGroupImage> = unsafeMutableNonNullPropertyOf(),
        val accessLock: ResourceAccessLock = ResourceAccessLock(),
    )

    val caches: Array<ImageCache> = Array(20) { ImageCache() }

    fun findCache(id: String): ImageCache? {
        return caches.firstOrNull { it.id.value0 == id && it.accessLock.tryUse() }
    }

    fun findCacheByImageId(id: String): ImageCache? = findCache(calcInternalIdByImageId(id))

    fun putCache(image: OfflineGroupImage) {
        putCache(calcInternalIdByImageId(image.imageId)).cacheOGI.value0 = image
    }

    fun putCache(id: String): ImageCache {
        fun ImageCache.postReturn(): ImageCache = also { cache ->
            cache.updateTime = currentTimeMillis()
            cache.id.value0 = id
        }

        caches.forEach { exists ->
            if (exists.id.value0 == id && exists.accessLock.tryInitialize()) {
                return exists.postReturn()
            }
        }

        // Try to use existing slot
        caches.forEach { exists ->
            if (exists.accessLock.tryInitialize()) {
                return exists.postReturn()
            }
        }

        val availableCaches = caches.filter { it.accessLock.lockIfNotUsing() }
        if (availableCaches.isNotEmpty()) {
            val target = availableCaches.minByOrNull { it.updateTime }!!
            availableCaches.forEach { if (it !== target) it.accessLock.unlock() }

            target.accessLock.setInitialized()
            return target.postReturn()
        }

        // No available sort. Force to override the last one
        val newCache = ImageCache()
        newCache.accessLock.setInitialized()
        newCache.postReturn()

        var idx = 0
        var lupd = Long.MAX_VALUE
        caches.forEachIndexed { index, imageCache ->
            val upd0 = imageCache.updateTime
            if (upd0 < lupd) {
                lupd = upd0
                idx = index
            }
        }
        caches[idx] = newCache

        return newCache
    }

    private fun calcInternalIdByImageId(imageId: String): String {
        return imageId.substring(1, imageId.indexOf('}'))
    }

    suspend fun patchOfflineGroupImage(
        group: GroupImpl,
        image: OfflineGroupImage,
    ) {
        if (image.fileId != null) return
        val iid = calcInternalIdByImageId(image.imageId)
        findCache(iid)?.withCache { cache ->
            cache.cacheOGI.value0?.let { cachedOGI ->
                image.fileId = cachedOGI.fileId
                return
            }
        }

        val bot = group.bot

        val response: ImgStore.GroupPicUp.Response = ImgStore.GroupPicUp(
            bot.client,
            uin = bot.id,
            groupCode = group.id,
            md5 = image.md5,
            size = 1,
        ).sendAndExpect(bot)

        when (response) {
            is ImgStore.GroupPicUp.Response.Failed -> {
                image.fileId = 0 // Failed
            }
            is ImgStore.GroupPicUp.Response.FileExists -> {
                image.fileId = response.fileId.toInt()
            }
            is ImgStore.GroupPicUp.Response.RequireUpload -> {
                image.fileId = response.fileId.toInt()
            }
        }

        putCache(iid).cacheOGI.value0 = image
    }

    /**
     * Ensures server holds the cache
     */
    suspend fun patchFriendImageToGroupImage(
        group: GroupImpl,
        image: FriendImage,
    ): OfflineGroupImage {
        val iid = calcInternalIdByImageId(image.imageId)
        findCache(iid)?.withCache { cache ->
            cache.cacheOGI.value0?.let { return it }
        }

        val bot = group.bot

        val response = ImgStore.GroupPicUp(
            bot.client,
            uin = bot.id,
            groupCode = group.id,
            md5 = image.md5,
            size = image.size
        ).sendAndExpect(bot.network)

        return OfflineGroupImage(
            imageId = image.imageId,
            width = image.width,
            height = image.height,
            size = if (response is ImgStore.GroupPicUp.Response.FileExists) {
                response.fileInfo.fileSize
            } else {
                image.size
            },
            imageType = image.imageType
        ).also { img ->
            when (response) {
                is ImgStore.GroupPicUp.Response.FileExists -> {
                    img.fileId = response.fileId.toInt()
                }
                is ImgStore.GroupPicUp.Response.RequireUpload -> {
                    img.fileId = response.fileId.toInt()
                }
                is ImgStore.GroupPicUp.Response.Failed -> {
                    img.fileId = 0
                }
            }
        }.also { img ->
            putCache(iid).cacheOGI.value0 = img
        }
    }

}
