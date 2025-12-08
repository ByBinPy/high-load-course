package ru.quipy.exceptions

class TooManyRequestsException(val retryAfterMs: Long) : RuntimeException()