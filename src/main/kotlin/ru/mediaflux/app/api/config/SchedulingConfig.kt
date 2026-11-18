package ru.mediaflux.app.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Расписание: сброс буфера позиций просмотра (`ProgressBuffer.flush`, MF-89). */
@Configuration
@EnableScheduling
class SchedulingConfig
