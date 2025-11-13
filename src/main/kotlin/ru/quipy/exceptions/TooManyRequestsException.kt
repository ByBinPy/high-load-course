package ru.quipy.exceptions

class TooManyRequestsException(val deadline: Long) : RuntimeException()