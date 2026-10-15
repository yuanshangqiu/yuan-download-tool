package com.yunx.app.data.network

/** Configuration for the optional StarSea Chinese-command resolver. */
object StarSeaConstants {
    /** Service root URL (HTTPS reverse proxy). */
    const val BASE_URL = "https://starsea-api.qwq.pics"
    const val RESOLVE_URL = "$BASE_URL/api/resolve"

    /**
     * API key is supplied at runtime so it is never committed to source control.
     * Set `YUNX_STARSEA_API_KEY` before starting the application to enable this
     * optional resolver. When unset, the resolver reports a configuration error.
     */
    val API_KEY: String
        get() = System.getenv("YUNX_STARSEA_API_KEY")?.trim().orEmpty()

    const val MAX_COMMAND_LENGTH = 120
}




