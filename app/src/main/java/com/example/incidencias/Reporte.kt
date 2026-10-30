package com.example.incidencias

data class Reporte(
    val id: String,
    val equipo: String,
    val descripcion: String,
    val prioridad: String,
    val estado: String,
    val creadoPor: String,
    val pendiente: Boolean // true = escritura aún sin confirmar por el servidor
)

val PRIORIDADES_REP = listOf("Baja", "Media", "Alta")