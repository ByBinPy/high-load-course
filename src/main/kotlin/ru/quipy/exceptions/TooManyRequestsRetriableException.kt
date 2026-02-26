package ru.quipy.exceptions

class TooManyRequestsRetriableException(val deadline: Long) : RuntimeException(){
}