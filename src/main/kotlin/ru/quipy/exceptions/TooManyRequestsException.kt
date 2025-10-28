package ru.quipy.exceptions

class TooManyRequestsException(val retryAfterSeconds: Int = 1_000) : RuntimeException()