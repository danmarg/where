package net.af0.where

fun createLocationProvider(): LocationProvider = FdroidLocationProvider()

fun createActivityHelper(): ActivityHelper = FdroidActivityHelper()
