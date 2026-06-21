package net.af0.where

fun createLocationProvider(): LocationProvider = GmsLocationProvider()

fun createActivityHelper(): ActivityHelper = GmsActivityHelper()
