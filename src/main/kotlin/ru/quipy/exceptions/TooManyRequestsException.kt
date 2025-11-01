package ru.quipy.exceptions

class TooManyRequestsException(val retryAfterMillisecond: Int) : RuntimeException()