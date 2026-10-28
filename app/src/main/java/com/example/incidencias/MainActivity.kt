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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Raiz()
                }
            }
        }
    }
}

@Composable
fun Raiz(vm: IncidenciasViewModel = viewModel()) {
    if (vm.uid == null) PantallaLogin(vm) else PantallaIncidencias(vm)
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
fun CajaLog(logs: List<String>) {
    Text("Bitácora", style = MaterialTheme.typography.titleMedium)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(8.dp)
    ) {
        Column {
            logs.forEach {
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

// ---------------------------------------------------------------- LOGIN

@Composable
fun PantallaLogin(vm: IncidenciasViewModel) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Iniciar sesión", style = MaterialTheme.typography.titleLarge) }

        item {
            OutlinedTextField(
                value = correo,
                onValueChange = { correo = it },
                label = { Text("Correo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = clave,
                onValueChange = { clave = it },
                label = { Text("Contraseña (mínimo 6 caracteres)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.iniciarSesion(correo, clave) }) { Text("Entrar") }
                OutlinedButton(onClick = { vm.registrar(correo, clave) }) { Text("Registrar cuenta") }
            }
        }

        item {
            OutlinedButton(
                onClick = { vm.probarSinSesion() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SEG-10: probar lectura y escritura SIN sesión") }
        }

        item { CajaLog(vm.logs) }
    }
}

// ---------------------------------------------------------------- PANTALLA PRINCIPAL

@Composable
fun PantallaIncidencias(vm: IncidenciasViewModel) {
    var nuevoTitulo by remember { mutableStateOf("") }
    var seleccionada by remember { mutableStateOf<Incidencia?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Incidencias", style = MaterialTheme.typography.titleLarge) }

        // Usuario y UID visibles (Fase 1, punto 4)
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Usuario: ${vm.email}")
                    SelectionContainer { Text("UID: ${vm.uid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    OutlinedButton(onClick = { vm.cerrarSesion() }) { Text("Cerrar sesión") }
                }
            }
        }

        item {
            Text(
                if (vm.online) "Servidor: Conectado" else "Servidor: SIN CONEXIÓN",
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
            item { Text("No tienes incidencias.") }
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
                    Text("${inc.estado} · ${inc.prioridad} · v${inc.version}")
                    Text(
                        "creadoPor: ${inc.creadoPor.take(8)}… · modificado por ${inc.modificadoPor}",
                        fontSize = 11.sp
                    )
                    if (inc.pendiente) {
                        Text("⏳ Pendiente de sincronizar", color = Color(0xFFEF6C00))
                    }
                }
            }
        }

        item { PanelSeguridad(vm) }

        item { CajaLog(vm.logs) }
    }
}

// ---------------------------------------------------------------- PANEL DE ATAQUES

@Composable
fun PanelSeguridad(vm: IncidenciasViewModel) {
    var idObjetivo by remember { mutableStateOf("") }
    var uidAjeno by remember { mutableStateOf("UID_DE_OTRO_USUARIO") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pruebas de seguridad (ataques desde el cliente)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "El ID de una incidencia es su título. Estos botones se saltan la interfaz " +
                        "y mandan la operación directo a la base: lo que debe bloquearlas son las reglas.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = idObjetivo,
                onValueChange = { idObjetivo = it },
                label = { Text("ID objetivo (ej. PRUEBA_B_001)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uidAjeno,
                onValueChange = { uidAjeno = it },
                label = { Text("UID ajeno (el de B, o cualquier otro texto)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(onClick = { vm.crearConUidAjeno(uidAjeno) }, modifier = Modifier.fillMaxWidth()) {
                Text("SEG-04 Crear con creadoPor ajeno")
            }
            OutlinedButton(onClick = { vm.leerAjena(idObjetivo) }, modifier = Modifier.fillMaxWidth()) {
                Text("SEG-05 Leer incidencia por ID")
            }
            OutlinedButton(onClick = { vm.modificarAjena(idObjetivo) }, modifier = Modifier.fillMaxWidth()) {
                Text("SEG-06 Modificar incidencia por ID")
            }
            OutlinedButton(onClick = { vm.eliminarAjena(idObjetivo) }, modifier = Modifier.fillMaxWidth()) {
                Text("SEG-07 Eliminar incidencia por ID")
            }
            OutlinedButton(
                onClick = { vm.cambiarPropietario(idObjetivo, uidAjeno) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SEG-09 Cambiar propietario (ID propio)") }

            Text("Extras", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { vm.leerTodas() }, modifier = Modifier.fillMaxWidth()) {
                Text("Leer TODAS las incidencias (sin filtro)")
            }
            OutlinedButton(onClick = { vm.probarEstadoInvalido(idObjetivo) }, modifier = Modifier.fillMaxWidth()) {
                Text("Estado inválido (ID propio)")
            }
            OutlinedButton(onClick = { vm.probarCampoInesperado(idObjetivo) }, modifier = Modifier.fillMaxWidth()) {
                Text("Campo inesperado (ID propio)")
            }
        }
    }
}

// ---------------------------------------------------------------- EDITOR

@Composable
fun EditorIncidencia(
    orig: Incidencia,
    modo: ModoGuardado,
    onGuardar: (String, String, String) -> Unit,
    onEliminar: () -> Unit,
    onCerrar: () -> Unit
) {
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