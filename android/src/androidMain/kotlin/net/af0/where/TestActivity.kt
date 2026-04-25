package net.af0.where

import androidx.activity.ComponentActivity

/**
 * A stub activity used solely for Robolectric unit tests (e.g., MapScreenTest).
 * 
 * Note: This lives in androidMain rather than androidUnitTest because Robolectric
 * cannot always resolve activities declared in test-only manifests when running 
 * against the 'release' build variant.
 */
class TestActivity : ComponentActivity()
