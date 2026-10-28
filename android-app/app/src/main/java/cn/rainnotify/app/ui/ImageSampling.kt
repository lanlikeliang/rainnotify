package cn.rainnotify.app.ui

object ImageSampling {
    fun sampleSize(width: Int, height: Int, limit: Int = 2048): Int {
        require(width > 0 && height > 0 && limit > 0)
        var sample = 1
        while (maxOf(width, height) / sample > limit) sample *= 2
        return sample
    }
}
