package ru.mediaflux.app.api.device

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.ContextValue
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import ru.mediaflux.app.api.catalog.utc
import ru.mediaflux.app.api.delivery.DeliveryBlockClient
import ru.mediaflux.app.api.delivery.DeliveryBlockReason
import ru.mediaflux.app.api.entity.AppDeviceEntity
import ru.mediaflux.app.api.guard.OperationGuard
import ru.mediaflux.app.api.security.AppPrincipal
import ru.mediaflux.app.api.security.CurrentPrincipal
import ru.mediaflux.app.api.support.error.AppException
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

data class DeviceRegisterInput(val installationId: UUID, val name: String, val platform: String, val appVersion: String)

data class DeviceView(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String?,
    val lastSeenAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val current: Boolean,
)

data class MeView(
    val id: String,
    val name: String?,
    val email: String?,
    /** null — без ограничения. */
    val deviceLimit: Int?,
    val device: DeviceView?,
    val deliveryBlock: DeliveryBlockReason?,
)

/** `me` и устройства (schema.graphqls, device.graphqls). */
@Controller
class DeviceController(
    private val devices: DeviceService,
    private val delivery: DeliveryBlockClient,
) {

    @QueryMapping
    fun me(@ContextValue(name = OperationGuard.DEVICE_KEY) device: Optional<DeviceState>): MeView {
        val principal = CurrentPrincipal.get()
        val current = device.orElse(null)?.takeUnless { it.revoked }?.let { state ->
            devices.list(principal).firstOrNull { it.id == state.id }?.view(principal)
        }
        return MeView(
            id = principal.user.id.toString(),
            name = principal.user.name,
            email = principal.user.email,
            deviceLimit = devices.limitFor(principal.user.id),
            device = current,
            deliveryBlock = delivery.reason(principal.user.id),
        )
    }

    @QueryMapping
    fun devices(): List<DeviceView> {
        val principal = CurrentPrincipal.get()
        return devices.list(principal).map { it.view(principal) }
    }

    @MutationMapping
    fun deviceRegister(@Argument input: DeviceRegisterInput): DeviceView {
        val principal = CurrentPrincipal.get()
        return devices.register(principal, DeviceRegistration(input.installationId, input.name, input.platform, input.appVersion))
            .view(principal)
    }

    @MutationMapping
    fun deviceRename(@Argument id: String, @Argument name: String): DeviceView {
        val principal = CurrentPrincipal.get()
        return devices.rename(principal, publicId(id), name).view(principal)
    }

    @MutationMapping
    fun deviceRevoke(@Argument id: String): Boolean = devices.revoke(CurrentPrincipal.get(), publicId(id))

    private fun publicId(id: String): UUID =
        runCatching { UUID.fromString(id) }.getOrNull() ?: throw AppException.notFound("Устройство $id")

    private fun AppDeviceEntity.view(principal: AppPrincipal) = DeviceView(
        id = publicId.toString(),
        name = name,
        platform = platform,
        appVersion = appVersion,
        lastSeenAt = lastSeenAt?.utc(),
        createdAt = createdAt.utc(),
        current = principal.sid != null && sessionId == principal.sid,
    )
}
