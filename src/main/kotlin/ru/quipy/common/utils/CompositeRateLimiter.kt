package ru.quipy.common.utils

class CompositeRateLimiter(
    private val rl1: RateLimiter,
    private val rl2: RateLimiter,
    private val mode: Mode = Mode.AND
) : RateLimiter {

    enum class Mode { AND, OR }

    override fun tick(): Boolean {
        return when (mode) {
            Mode.AND -> rl1.tick() && rl2.tick()
            Mode.OR -> rl1.tick() || rl2.tick()
        }
    }
}
