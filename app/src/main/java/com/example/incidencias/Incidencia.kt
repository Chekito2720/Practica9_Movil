package com.example.incidencias

data class Incidencia(
    val id: String,
    val titulo: String,
    val estado: String,
    val prioridad: String,
    val version: Long,
    val modificadoPor: String,
    val creadoPor: String, // UID del propietario
    val pendiente: Boolean // true = escritura aún sin confirmar por el servidor
)

val ESTADOS = listOf("Pendiente", "En proceso", "Resuelta")
val PRIORIDADES = listOf("Baja", "Media", "Alta", "Crítica")

enum class ModoGuardado(val etiqueta: String) {
    COMPLETO("Completo (set)"),
    CAMPOS("Solo campos (update)"),
    TRANSACCION("Transacción + versión")
}