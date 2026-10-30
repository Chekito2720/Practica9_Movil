package com.example.incidencias

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportesViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()
    private val raiz: DatabaseReference = db.getReference("reportes")

    private val datos = mutableMapOf<String, Reporte>()
    private val pendientes = mutableSetOf<String>()

    var reportes by mutableStateOf<List<Reporte>>(emptyList())
        private set
    var logs by mutableStateOf<List<String>>(emptyList())
        private set
    var resultadoConsulta by mutableStateOf("")
        private set
    var mensajeValidacion by mutableStateOf("")
        private set
    var online by mutableStateOf(false)
        private set

    // ---------- Conexión con el servidor ----------

    private val escuchaConexion = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val conectado = snapshot.getValue(Boolean::class.java) == true
            online = conectado
            log(if (conectado) "Servidor Firebase: CONECTADO" else "Servidor Firebase: DESCONECTADO")
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    // ---------- Lectura en tiempo real ----------

    private val escuchaReportes = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            actualizar(snapshot, "ADDED")
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            actualizar(snapshot, "MODIFIED")
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            val id = snapshot.key ?: return
            datos.remove(id)
            refrescar()
            if (id !in pendientes) log("Servidor → REMOVED reporte $id")
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

        override fun onCancelled(error: DatabaseError) {
            log(
                if (error.code == DatabaseError.PERMISSION_DENIED)
                    "🔒 Lectura RECHAZADA por las reglas de seguridad: ${error.message}"
                else "ERROR de escucha: ${error.message}"
            )
        }
    }

    // Solo escuchamos mientras hay sesión iniciada
    private val escuchaAuth = FirebaseAuth.AuthStateListener { fa ->
        raiz.removeEventListener(escuchaReportes)
        datos.clear()
        pendientes.clear()
        refrescar()
        if (fa.currentUser != null) raiz.addChildEventListener(escuchaReportes)
    }

    init {
        db.getReference(".info/connected").addValueEventListener(escuchaConexion)
        auth.addAuthStateListener(escuchaAuth)
    }

    private fun actualizar(s: DataSnapshot, tipo: String) {
        val id = s.key ?: return
        val r = Reporte(
            id = id,
            equipo = s.child("equipo").getValue(String::class.java) ?: "",
            descripcion = s.child("descripcion").getValue(String::class.java) ?: "",
            prioridad = s.child("prioridad").getValue(String::class.java) ?: "Media",
            estado = s.child("estado").getValue(String::class.java) ?: "Abierto",
            creadoPor = s.child("creadoPor").getValue(String::class.java) ?: "?",
            pendiente = false
        )
        datos[id] = r
        refrescar()
        if (id !in pendientes) {
            log("Servidor → $tipo reporte $id (${r.equipo}, prioridad ${r.prioridad})")
        }
    }

    private fun refrescar() {
        reportes = datos.values
            .sortedBy { it.id }
            .map { it.copy(pendiente = it.id in pendientes) }
    }

    // ---------- Utilidades ----------

    fun log(msg: String) {
        val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logs = (listOf("[$hora] $msg") + logs).take(80)
    }

    /** true si el error es una denegación de las reglas (no un fallo de red). */
    private fun denegado(e: Exception?) =
        e?.message?.contains("permission", ignoreCase = true) == true

    private fun <T> Task<T>.seguirCon(fin: (Exception?) -> Unit) {
        addOnSuccessListener { fin(null) }
        addOnFailureListener { fin(it) }
    }

    private fun idValido(id: String) = id.isNotEmpty() && !Regex("[.#\$\\[\\]/]").containsMatchIn(id)

    /**
     * Realtime Database actualiza la lista local al instante, antes de que el servidor confirme.
     * Marcamos el id como "pendiente" hasta la confirmación real.
     */
    private fun seguir(
        nombre: String,
        id: String,
        deberiaRechazarse: Boolean = false,
        iniciar: ((Exception?) -> Unit) -> Unit
    ) {
        var terminada = false
        var avisoTimeout = false

        pendientes.add(id)
        refrescar()

        iniciar { error ->
            terminada = true
            pendientes.remove(id)
            refrescar()
            when {
                error == null && deberiaRechazarse ->
                    log("⚠ $nombre: ACEPTADO por el servidor (¡las reglas NO lo bloquearon!)")
                error == null && avisoTimeout ->
                    log("✔ $nombre: confirmado tardíamente al recuperar la red")
                error == null -> log("✔ $nombre: CONFIRMADO por el servidor")
                deberiaRechazarse && denegado(error) ->
                    log("🔒 $nombre: RECHAZADO por las reglas de seguridad (esperado) → ${error?.message}")
                denegado(error) ->
                    log("🔒 $nombre: DENEGADO por las reglas de seguridad → ${error?.message}")
                else -> log("✖ $nombre: ${error?.message}")
            }
        }

        viewModelScope.launch {
            delay(5000)
            if (!terminada) {
                avisoTimeout = true
                log("⚠ $nombre: SIN respuesta del servidor (¿sin conexión?). Escritura pendiente.")
            }
        }
    }

    // ---------- ALTA (TC01, TC02, TC03) ----------

    fun alta(idEntrada: String, equipo: String, descripcion: String, prioridad: String) {
        val id = idEntrada.trim()
        val e = equipo.trim()
        val d = descripcion.trim()

        // TC02: validación en la app, antes de enviar nada al servidor
        val faltantes = mutableListOf<String>()
        if (id.isEmpty()) faltantes.add("ID")
        if (e.isEmpty()) faltantes.add("equipo")
        if (d.isEmpty()) faltantes.add("descripción")
        if (faltantes.isNotEmpty()) {
            mensajeValidacion = "⚠ Campos obligatorios vacíos: ${faltantes.joinToString(", ")}"
            log("Validación local: faltan ${faltantes.joinToString(", ")}. NO se envió nada al servidor.")
            return
        }
        if (!idValido(id)) {
            mensajeValidacion = "⚠ El ID no puede contener . # \$ [ ] /"
            log("Validación local: ID con caracteres no permitidos. NO se envió nada al servidor.")
            return
        }
        mensajeValidacion = ""

        val miUid = auth.currentUser?.uid ?: return
        val nuevo = mapOf(
            "equipo" to e,
            "descripcion" to d,
            "prioridad" to prioridad,
            "estado" to "Abierto",
            "creadoPor" to miUid
        )

        // TC03: política explícita → NO se sobrescribe. Solo se crea si el ID no existe.
        seguir("Alta $id", id) { fin ->
            raiz.child(id).runTransaction(object : Transaction.Handler {
                override fun doTransaction(actual: MutableData): Transaction.Result {
                    if (actual.value != null) return Transaction.abort()
                    actual.value = nuevo
                    return Transaction.success(actual)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    snapshot: DataSnapshot?
                ) {
                    when {
                        error != null -> fin(error.toException())
                        !committed -> fin(
                            IllegalStateException(
                                "ID DUPLICADO: ya existe un reporte con el ID $id. No se sobrescribió."
                            )
                        )
                        else -> fin(null)
                    }
                }
            })
        }
    }

    // ---------- CONSULTA (TC06, y verificación de TC04, TC05, TC08) ----------

    fun consultar(idEntrada: String) {
        val id = idEntrada.trim()
        if (id.isEmpty()) {
            resultadoConsulta = "Escribe un ID para consultar"
            return
        }
        resultadoConsulta = "Consultando $id ..."
        raiz.child(id).get()
            .addOnSuccessListener { s ->
                if (!s.exists()) {
                    resultadoConsulta = "No existe ningún reporte con el ID $id"
                    log("Consulta $id: resultado vacío, sin errores")
                } else {
                    val equipo = s.child("equipo").getValue(String::class.java)
                    val desc = s.child("descripcion").getValue(String::class.java)
                    val prio = s.child("prioridad").getValue(String::class.java)
                    val estado = s.child("estado").getValue(String::class.java)
                    resultadoConsulta =
                        "$id → equipo: $equipo · descripción: $desc · prioridad: $prio · estado: $estado"
                    log("Consulta $id: ENCONTRADO (prioridad=$prio, estado=$estado)")
                }
            }
            .addOnFailureListener {
                resultadoConsulta =
                    if (denegado(it)) "🔒 Consulta rechazada por las reglas de seguridad"
                    else "✖ Error al consultar: ${it.message}"
                log("✖ Consulta $id: ${it.message}")
            }
    }

    // ---------- ACTUALIZACIÓN (TC04) ----------

    fun cambiarPrioridad(idEntrada: String, prioridad: String) {
        val id = idEntrada.trim()
        if (id.isEmpty()) {
            log("Escribe el ID del reporte")
            return
        }
        // Primero comprobamos que exista: así no se crea un registro fantasma
        raiz.child(id).get()
            .addOnSuccessListener { s ->
                if (!s.exists()) {
                    log("✖ Actualizar $id: el reporte NO existe. No se creó ni modificó nada.")
                    return@addOnSuccessListener
                }
                seguir("Actualizar prioridad de $id → $prioridad", id) { fin ->
                    raiz.child(id).updateChildren(mapOf("prioridad" to prioridad)).seguirCon { error ->
                        fin(error)
                        if (error == null) consultar(id) // volvemos a consultar al servidor
                    }
                }
            }
            .addOnFailureListener {
                log("✖ Actualizar $id: no se pudo comprobar si existe → ${it.message}")
            }
    }

    // ---------- ELIMINACIÓN (TC05) ----------

    fun eliminar(idEntrada: String) {
        val id = idEntrada.trim()
        if (id.isEmpty()) {
            log("Escribe el ID del reporte")
            return
        }
        raiz.child(id).get()
            .addOnSuccessListener { s ->
                if (!s.exists()) {
                    log("✖ Eliminar $id: el reporte NO existe. No se hizo nada.")
                    return@addOnSuccessListener
                }
                seguir("Eliminar $id", id) { fin ->
                    raiz.child(id).removeValue().seguirCon { error ->
                        fin(error)
                        if (error == null) consultar(id) // verificamos que ya no existe
                    }
                }
            }
            .addOnFailureListener {
                log("✖ Eliminar $id: no se pudo comprobar si existe → ${it.message}")
            }
    }

    // ---------- Pruebas de error que se saltan la validación de la app ----------

    /** TC02 (extra): enviar un reporte vacío directo al servidor. Debe rechazarlo la regla de validación. */
    fun altaVaciaForzada(idEntrada: String) {
        val id = idEntrada.trim().ifEmpty { "REP_VACIO" }
        val miUid = auth.currentUser?.uid ?: return
        val vacio = mapOf(
            "equipo" to "",
            "descripcion" to "",
            "prioridad" to "Media",
            "estado" to "Abierto",
            "creadoPor" to miUid
        )
        seguir("TC02 extra: alta con equipo y descripción vacíos", id, true) { fin ->
            raiz.child(id).setValue(vacio).seguirCon(fin)
        }
    }

    /** TC06 (extra): actualizar un ID inexistente SIN comprobar. Las reglas deben impedir el registro fantasma. */
    fun actualizarInexistenteForzado(idEntrada: String) {
        val id = idEntrada.trim()
        if (id.isEmpty()) {
            log("Escribe el ID inexistente (ej. REP999)")
            return
        }
        seguir("TC06 extra: actualizar $id sin comprobar existencia", id, true) { fin ->
            raiz.child(id).updateChildren(mapOf("prioridad" to "Alta")).seguirCon(fin)
        }
    }

    override fun onCleared() {
        db.getReference(".info/connected").removeEventListener(escuchaConexion)
        raiz.removeEventListener(escuchaReportes)
        auth.removeAuthStateListener(escuchaAuth)
        super.onCleared()
    }
}