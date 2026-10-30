package com.example.incidencias

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PantallaReportes(vm: ReportesViewModel = viewModel()) {
    var idAlta by remember { mutableStateOf("REP001") }
    var equipo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var prioridadAlta by remember { mutableStateOf("Media") }

    var idOp by remember { mutableStateOf("REP001") }
    var prioridadNueva by remember { mutableStateOf("Alta") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Reportes", style = MaterialTheme.typography.titleLarge) }

        item {
            Text(
                if (vm.online) "Servidor: Conectado" else "Servidor: SIN CONEXIÓN",
                color = if (vm.online) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }

        // ---------------- Alta ----------------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alta de reporte", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = idAlta,
                        onValueChange = { idAlta = it },
                        label = { Text("ID (ej. REP001)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = equipo,
                        onValueChange = { equipo = it },
                        label = { Text("Equipo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = descripcion,
                        onValueChange = { descripcion = it },
                        label = { Text("Descripción") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Prioridad", style = MaterialTheme.typography.labelLarge)
                    Opciones(PRIORIDADES_REP, prioridadAlta) { prioridadAlta = it }
                    if (vm.mensajeValidacion.isNotEmpty()) {
                        Text(vm.mensajeValidacion, color = Color(0xFFC62828))
                    }
                    Button(onClick = { vm.alta(idAlta, equipo, descripcion, prioridadAlta) }) {
                        Text("Guardar reporte")
                    }
                }
            }
        }

        // ---------------- Consulta, actualización, eliminación ----------------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Consultar, actualizar o eliminar", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = idOp,
                        onValueChange = { idOp = it },
                        label = { Text("ID del reporte") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { vm.consultar(idOp) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Consultar")
                    }
                    if (vm.resultadoConsulta.isNotEmpty()) {
                        Text(vm.resultadoConsulta, fontSize = 13.sp)
                    }
                    Text("Nueva prioridad", style = MaterialTheme.typography.labelLarge)
                    Opciones(PRIORIDADES_REP, prioridadNueva) { prioridadNueva = it }
                    Button(
                        onClick = { vm.cambiarPrioridad(idOp, prioridadNueva) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cambiar prioridad") }
                    OutlinedButton(onClick = { vm.eliminar(idOp) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Eliminar reporte")
                    }
                }
            }
        }

        // ---------------- Pruebas de error ----------------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pruebas de error (saltan la validación de la app)", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.altaVaciaForzada(idAlta) }, modifier = Modifier.fillMaxWidth()) {
                        Text("TC02 extra: enviar reporte vacío al servidor")
                    }
                    OutlinedButton(
                        onClick = { vm.actualizarInexistenteForzado(idOp) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("TC06 extra: actualizar ID inexistente sin comprobar") }
                }
            }
        }

        // ---------------- Lista en tiempo real ----------------
        item { Text("Reportes en el servidor (${vm.reportes.size})", style = MaterialTheme.typography.titleMedium) }

        if (vm.reportes.isEmpty()) {
            item { Text("No hay reportes.") }
        }

        items(vm.reportes, key = { it.id }) { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.id, style = MaterialTheme.typography.titleMedium)
                    Text("${r.equipo} · ${r.descripcion}")
                    Text("Prioridad: ${r.prioridad} · Estado: ${r.estado}")
                    if (r.pendiente) {
                        Text("⏳ Pendiente de sincronizar", color = Color(0xFFEF6C00))
                    }
                }
            }
        }

        item { CajaLog(vm.logs) }
    }
}