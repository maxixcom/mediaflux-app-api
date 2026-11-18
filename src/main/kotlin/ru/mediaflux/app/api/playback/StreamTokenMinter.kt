package ru.mediaflux.app.api.playback

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import ru.mediaflux.app.api.config.StreamTokenProperties
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

data class StreamToken(val value: String, val expiresAt: Instant)

/**
 * Владельческий токен стрима — тот же, что выпускает кабинет (`console/.../StreamTokenMinter`,
 * STREAM_AUTHORIZATION.md §3.1): HS256 с общим со стримером секретом, `{code, purpose=owner, iat, exp}`. Стример
 * принимает его и для медиа, и для канала (`tokenCode == channelCode`).
 */
@Component
class StreamTokenMinter(private val props: StreamTokenProperties) {

    private val key by lazy { Keys.hmacShaKeyFor(props.secret.toByteArray(StandardCharsets.UTF_8)) }

    fun mint(code: String): StreamToken {
        val now = Instant.now()
        val exp = now.plusSeconds(props.ownerTtlSeconds)
        val value = Jwts.builder()
            .claim("code", code)
            .claim("purpose", PURPOSE_OWNER)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
        return StreamToken(value, exp)
    }

    private companion object {
        const val PURPOSE_OWNER = "owner"
    }
}
