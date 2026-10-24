package com.asapp

object Keys {
    // 1) Free key from https://www.pexels.com/api/  (paste it between the quotes)
    val PEXELS = "V7DiJiYLYpEAkMJIxzr3KbSy6eiTS90rPlLDK0L503r0sIgOYnQfutcA"

    // 2) Firebase console -> Project settings -> General -> Web API Key
    val FB_API_KEY = "AIzaSyCxvCn8-j5hwOeBxpXyHe_RE58RK7pew2A"

    // 3) Firebase console -> Realtime Database -> the URL at the top, e.g.
    //    https://myproject-default-rtdb.firebaseio.com
    val FB_DB_URL = "https://as-app-12fa9-default-rtdb.firebaseio.com"

    // 4) OPTIONAL, for calls that fail on mobile data: free TURN server (e.g. metered.ca Open Relay dashboard)
    //    TURN_URL can hold several addresses separated by commas.
    val TURN_URL = ""
    val TURN_USER = ""
    val TURN_PASS = ""
}
