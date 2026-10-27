package com.example.incidencias

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.incidencias.ESTADOS
import com.example.incidencias.Incidencia
import com.example.incidencias.PRIORIDADES
import com.example.incidencias.IncidenciasViewModel
import com.example.incidencias.ModoGuardado


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PantallaIncidencias()
                }
            }
        }
    }
}

@Composable
fun Opciones(opciones: List<String>, seleccion: String, onSeleccion: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        opciones.forEach { o ->
            FilterChip(
                selected = o == seleccion,
                onClick = { onSeleccion(o) },
                label = { Text(o) }
            )
        }
    }
}

@Composable
fun PantallaIncidencias(vm: IncidenciasViewModel = viewModel()) {
    var nuevoTitulo by remember { mutableStateOf("") }
    var seleccionada by remember { mutableStateOf<Incidencia?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Incidencias · Dispositivo ${vm.dispositivo}",
                style = MaterialTheme.typography.titleLarge
            )
        }

        item {
            Opciones(listOf("A", "B"), vm.dispositivo) { vm.cambiarDispositivo(it) }
        }

        item {
            Text(
                if (vm.online) "Red: Conectado" else "Red: SIN CONEXIÓN",
                color = if (vm.online) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }

        item {
            Text("Política de guardado", style = MaterialTheme.typography.labelLarge)
            Opciones(
                ModoGuardado.entries.map { it.etiqueta },
                vm.modo.etiqueta
            ) { etiqueta ->
                vm.cambiarModo(ModoGuardado.entries.first { it.etiqueta == etiqueta })
            }
        }

        item {
            Row {
                OutlinedTextField(
                    value = nuevoTitulo,
                    onValueChange = { nuevoTitulo = it },
                    label = { Text("Nueva incidencia (ej. PRUEBA_A_001)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        vm.crear(nuevoTitulo.trim())
                        nuevoTitulo = ""
                    },
                    enabled = nuevoTitulo.isNotBlank()
                ) { Text("Crear") }
            }
        }

        // Editor (edición separada del guardado)
        seleccionada?.let { orig ->
            item {
                EditorIncidencia(
                    orig = orig,
                    modo = vm.modo,
                    onGuardar = { t, e, p ->
                        vm.guardar(orig, t, e, p)
                        seleccionada = null
                    },
                    onEliminar = {
                        vm.eliminar(orig)
                        seleccionada = null
                    },
                    onCerrar = { seleccionada = null }
                )
            }
        }

        if (vm.incidencias.isEmpty()) {
            item { Text("No hay incidencias.") }
        }

        items(vm.incidencias, key = { it.id }) { inc ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        seleccionada = inc
                        vm.log("Abierta \"${inc.titulo}\" (versión ${inc.version})")
                    }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(inc.titulo, style = MaterialTheme.typography.titleMedium)
                    Text("${inc.estado} · ${inc.prioridad} · v${inc.version} · por ${inc.modificadoPor}")
                    if (inc.pendiente) {
                        Text("⏳ Pendiente de sincronizar", color = Color(0xFFEF6C00))
                    }
                }
            }
        }

        // Bitácora para evidencias y tiempos
        item {
            Text("Bitácora", style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(8.dp)
            ) {
                Column {
                    vm.logs.forEach {
                        Text(
                            it,
                            color = Color(0xFF99FF99),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditorIncidencia(
    orig: Incidencia,
    modo: ModoGuardado,
    onGuardar: (String, String, String) -> Unit,
    onEliminar: () -> Unit,
    onCerrar: () -> Unit
) {
    // Copia local de lo que vimos al abrir: no cambia aunque el otro dispositivo modifique
    var titulo by remember(orig) { mutableStateOf(orig.titulo) }
    var estado by remember(orig) { mutableStateOf(orig.estado) }
    var prioridad by remember(orig) { mutableStateOf(orig.prioridad) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Editando: ${orig.titulo} (v${orig.version})",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = titulo,
                onValueChange = { titulo = it },
                label = { Text("Título") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Estado", style = MaterialTheme.typography.labelLarge)
            Opciones(ESTADOS, estado) { estado = it }
            Text("Prioridad", style = MaterialTheme.typography.labelLarge)
            Opciones(PRIORIDADES, prioridad) { prioridad = it }
            Text("Política: ${modo.etiqueta}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onGuardar(titulo, estado, prioridad) }) { Text("Guardar") }
                Button(
                    onClick = onEliminar,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Eliminar") }
                OutlinedButton(onClick = onCerrar) { Text("Cerrar") }
            }
        }
    }
}