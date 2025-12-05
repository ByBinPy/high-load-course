package ru.quipy.exceptions

class TooManyRequestsException(val retryAfterMillisecond: Long) : RuntimeException()