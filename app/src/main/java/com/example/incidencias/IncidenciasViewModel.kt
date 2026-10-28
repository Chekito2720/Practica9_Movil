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
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IncidenciasViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()
    private val raiz: DatabaseReference = db.getReference("incidencias")

    private val datos = mutableMapOf<String, Incidencia>()
    private val pendientes = mutableSetOf<String>() // ids con escritura sin confirmar
    private var consulta: Query? = null

    var uid by mutableStateOf(auth.currentUser?.uid)
        private set
    var email by mutableStateOf(auth.currentUser?.email)
        private set
    var modo by mutableStateOf(ModoGuardado.CAMPOS)
        private set
    var online by mutableStateOf(false)
        private set
    var incidencias by mutableStateOf<List<Incidencia>>(emptyList())
        private set
    var logs by mutableStateOf<List<String>>(emptyList())
        private set

    private val usuario: String get() = email ?: "?"

    // ---------- Conexión con el servidor ----------

    private val escuchaConexion = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val conectado = snapshot.getValue(Boolean::class.java) == true
            online = conectado
            log(if (conectado) "Servidor Firebase: CONECTADO" else "Servidor Firebase: DESCONECTADO")
        }

        override fun onCancelled(error: DatabaseError) {}
    }

    // ---------- Lectura en tiempo real (solo MIS incidencias) ----------

    private val escuchaIncidencias = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            actualizar(snapshot, "ADDED")
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            actualizar(snapshot, "MODIFIED")
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            val id = snapshot.key ?: return
            val previo = datos.remove(id)
            refrescar()
            if (id !in pendientes) log("Servidor → REMOVED \"${previo?.titulo ?: id}\"")
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

    // ---------- Estado de la sesión ----------

    private val escuchaAuth = FirebaseAuth.AuthStateListener { fa ->
        val u = fa.currentUser
        uid = u?.uid
        email = u?.email
        desconectarIncidencias()
        datos.clear()
        pendientes.clear()
        refrescar()
        if (u != null) {
            log("Sesión activa: ${u.email} · UID ${u.uid}")
            conectarIncidencias(u.uid)
        } else {
            log("Sin sesión iniciada")
        }
    }

    init {
        db.getReference(".info/connected").addValueEventListener(escuchaConexion)
        auth.addAuthStateListener(escuchaAuth)
    }

    private fun conectarIncidencias(uidActual: String) {
        val q = raiz.orderByChild("creadoPor").equalTo(uidActual)
        q.addChildEventListener(escuchaIncidencias)
        consulta = q
    }

    private fun desconectarIncidencias() {
        consulta?.removeEventListener(escuchaIncidencias)
        consulta = null
    }

    private fun actualizar(s: DataSnapshot, tipo: String) {
        val id = s.key ?: return
        val inc = Incidencia(
            id = id,
            titulo = s.child("titulo").getValue(String::class.java) ?: "",
            estado = s.child("estado").getValue(String::class.java) ?: "Pendiente",
            prioridad = s.child("prioridad").getValue(String::class.java) ?: "Media",
            version = (s.child("version").value as? Number)?.toLong() ?: 1L,
            modificadoPor = s.child("modificadoPor").getValue(String::class.java) ?: "?",
            creadoPor = s.child("creadoPor").getValue(String::class.java) ?: "?",
            pendiente = false
        )
        datos[id] = inc
        refrescar()
        if (id !in pendientes) {
            log("Servidor → $tipo \"${inc.titulo}\" (${inc.estado}/${inc.prioridad}) por ${inc.modificadoPor}")
        }
    }

    private fun refrescar() {
        incidencias = datos.values
            .sortedBy { it.titulo }
            .map { it.copy(pendiente = it.id in pendientes) }
    }

    // ---------- Utilidades ----------

    fun log(msg: String) {
        val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logs = (listOf("[$hora] $msg") + logs).take(80)
    }

    fun cambiarModo(m: ModoGuardado) {
        modo = m
    }

    private fun idDesde(titulo: String) =
        titulo.trim().replace(Regex("[.#\$\\[\\]/]"), "_")

    /** true si el error es una denegación de las reglas (no un fallo de red). */
    private fun denegado(e: Exception?) =
        e?.message?.contains("permission", ignoreCase = true) == true

    private fun <T> Task<T>.seguirCon(fin: (Exception?) -> Unit) {
        addOnSuccessListener { fin(null) }
        addOnFailureListener { fin(it) }
    }

    /**
     * Realtime Database actualiza la lista local al instante, antes de que el servidor confirme.
     * Marcamos el id como "pendiente" hasta la confirmación real.
     * deberiaRechazarse = true: es un intento de ataque; lo normal es que las reglas lo rechacen.
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
                error == null && deberiaRechazarse -> log(
                    "⚠ $nombre: ACEPTADO por el servidor " +
                            "(si el recurso es de otro usuario, las reglas NO lo bloquearon)"
                )
                error == null && avisoTimeout ->
                    log("✔ $nombre: confirmado tardíamente al recuperar la red")
                error == null -> log("✔ $nombre: CONFIRMADO por el servidor")
                deberiaRechazarse && denegado(error) ->
                    log("🔒 $nombre: RECHAZADO por las reglas de seguridad (esperado) → ${error.message}")
                denegado(error) ->
                    log("🔒 $nombre: DENEGADO por las reglas de seguridad → ${error.message}")
                else -> log("✖ $nombre: ${error.message}")
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

    // ---------- Autenticación ----------

    fun iniciarSesion(correo: String, clave: String) {
        if (correo.isBlank() || clave.isBlank()) {
            log("Escribe correo y contraseña")
            return
        }
        auth.signInWithEmailAndPassword(correo.trim(), clave)
            .addOnSuccessListener { log("✔ Autenticación ACEPTADA") }
            .addOnFailureListener { log("✖ Autenticación RECHAZADA: ${it.message}") }
    }

    fun registrar(correo: String, clave: String) {
        if (correo.isBlank() || clave.isBlank()) {
            log("Escribe correo y contraseña")
            return
        }
        auth.createUserWithEmailAndPassword(correo.trim(), clave)
            .addOnSuccessListener { log("✔ Cuenta creada (la sesión queda iniciada)") }
            .addOnFailureListener { log("✖ No se pudo crear la cuenta: ${it.message}") }
    }

    fun cerrarSesion() {
        auth.signOut()
    }

    // ---------- Operaciones normales ----------

    fun crear(titulo: String) {
        val miUid = uid ?: return
        val id = idDesde(titulo)
        val nuevo = mapOf(
            "titulo" to titulo,
            "estado" to "Pendiente",
            "prioridad" to "Media",
            "version" to 1L,
            "modificadoPor" to usuario,
            "creadoPor" to miUid
        )
        seguir("Crear \"$titulo\"", id) { fin ->
            raiz.child(id).setValue(nuevo).seguirCon(fin)
        }
    }

    fun guardar(original: Incidencia, titulo: String, estado: String, prioridad: String) {
        val ref = raiz.child(original.id)
        val nombre = "Guardar \"${original.titulo}\" [${modo.name}]"

        when (modo) {
            ModoGuardado.COMPLETO -> {
                val completo = mapOf(
                    "titulo" to titulo,
                    "estado" to estado,
                    "prioridad" to prioridad,
                    "version" to original.version + 1,
                    "modificadoPor" to usuario,
                    "creadoPor" to original.creadoPor
                )
                seguir(nombre, original.id) { fin -> ref.setValue(completo).seguirCon(fin) }
            }

            ModoGuardado.CAMPOS -> {
                val cambios = mutableMapOf<String, Any>()
                if (titulo != original.titulo) cambios["titulo"] = titulo
                if (estado != original.estado) cambios["estado"] = estado
                if (prioridad != original.prioridad) cambios["prioridad"] = prioridad
                cambios["version"] = ServerValue.increment(1)
                cambios["modificadoPor"] = usuario
                seguir(nombre, original.id) { fin -> ref.updateChildren(cambios).seguirCon(fin) }
            }

            ModoGuardado.TRANSACCION -> {
                seguir(nombre, original.id) { fin ->
                    ref.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(actual: MutableData): Transaction.Result {
                            if (actual.value == null) return Transaction.success(actual)
                            val versionActual =
                                (actual.child("version").value as? Number)?.toLong() ?: 1L
                            if (versionActual != original.version) return Transaction.abort()

                            actual.child("titulo").value = titulo
                            actual.child("estado").value = estado
                            actual.child("prioridad").value = prioridad
                            actual.child("version").value = original.version + 1
                            actual.child("modificadoPor").value = usuario
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
                                        "CONFLICTO: el registro cambió (tú editabas la versión " +
                                                "${original.version}). Recarga y reintenta."
                                    )
                                )
                                snapshot?.exists() != true ->
                                    fin(IllegalStateException("El registro ya no existe"))
                                else -> fin(null)
                            }
                        }
                    })
                }
            }
        }
    }

    fun eliminar(original: Incidencia) {
        seguir("Eliminar \"${original.titulo}\"", original.id) { fin ->
            raiz.child(original.id).removeValue().seguirCon(fin)
        }
    }

    // ---------- Pruebas de ataque desde el cliente (Práctica 10) ----------

    private fun idObjetivoValido(idObjetivo: String): String? {
        val id = idObjetivo.trim()
        if (id.isEmpty()) {
            log("Escribe el ID objetivo (el título de la incidencia)")
            return null
        }
        return id
    }

    /** SEG-04: crear una incidencia declarando como dueño a otro UID. */
    fun crearConUidAjeno(uidAjeno: String) {
        val id = "SEG04_SUPLANTACION"
        val falso = mapOf(
            "titulo" to id,
            "estado" to "Pendiente",
            "prioridad" to "Media",
            "version" to 1L,
            "modificadoPor" to usuario,
            "creadoPor" to uidAjeno.trim()
        )
        seguir("SEG-04 Crear con creadoPor ajeno", id, true) { fin ->
            raiz.child(id).setValue(falso).seguirCon(fin)
        }
    }

    /** SEG-05: leer directamente una incidencia por su ID. */
    fun leerAjena(idObjetivo: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        log("SEG-05 Leyendo directamente /incidencias/$id ...")
        raiz.child(id).get()
            .addOnSuccessListener { s ->
                if (s.exists()) {
                    log(
                        "⚠ SEG-05: LECTURA ACEPTADA, leyó \"${s.child("titulo").value}\" " +
                                "cuyo dueño es ${s.child("creadoPor").value}"
                    )
                } else {
                    log("SEG-05: el nodo no existe")
                }
            }
            .addOnFailureListener {
                log(
                    if (denegado(it)) "🔒 SEG-05: lectura RECHAZADA por las reglas (esperado) → ${it.message}"
                    else "✖ SEG-05: ${it.message}"
                )
            }
    }

    /** SEG-06: modificar una incidencia por su ID. */
    fun modificarAjena(idObjetivo: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        seguir("SEG-06 Modificar \"$id\"", id, true) { fin ->
            raiz.child(id)
                .updateChildren(mapOf("estado" to "Resuelta", "modificadoPor" to usuario))
                .seguirCon(fin)
        }
    }

    /** SEG-07: eliminar una incidencia por su ID. */
    fun eliminarAjena(idObjetivo: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        seguir("SEG-07 Eliminar \"$id\"", id, true) { fin ->
            raiz.child(id).removeValue().seguirCon(fin)
        }
    }

    /** SEG-09: cambiar el propietario de una incidencia propia. */
    fun cambiarPropietario(idObjetivo: String, nuevoUid: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        seguir("SEG-09 Cambiar creadoPor de \"$id\"", id, true) { fin ->
            raiz.child(id).updateChildren(mapOf("creadoPor" to nuevoUid.trim())).seguirCon(fin)
        }
    }

    /** Extra: intentar leer TODAS las incidencias sin filtrar por mi UID. */
    fun leerTodas() {
        log("EXTRA Leyendo /incidencias completo (sin filtro) ...")
        raiz.get()
            .addOnSuccessListener {
                log("⚠ EXTRA: lectura completa ACEPTADA (${it.childrenCount} registros). ¡Revisa las reglas!")
            }
            .addOnFailureListener {
                log(
                    if (denegado(it)) "🔒 EXTRA: lectura completa RECHAZADA por las reglas (esperado) → ${it.message}"
                    else "✖ EXTRA: ${it.message}"
                )
            }
    }

    /** Extra (validación): poner un estado que no existe en una incidencia propia. */
    fun probarEstadoInvalido(idObjetivo: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        seguir("EXTRA Estado inválido en \"$id\"", id, true) { fin ->
            raiz.child(id).updateChildren(mapOf("estado" to "Hackeado")).seguirCon(fin)
        }
    }

    /** Extra (validación): agregar un campo que el modelo no contempla. */
    fun probarCampoInesperado(idObjetivo: String) {
        val id = idObjetivoValido(idObjetivo) ?: return
        seguir("EXTRA Campo inesperado en \"$id\"", id, true) { fin ->
            raiz.child(id).updateChildren(mapOf("campoExtra" to "valor")).seguirCon(fin)
        }
    }

    /** SEG-10: con la sesión cerrada, intentar escribir y leer. */
    fun probarSinSesion() {
        if (auth.currentUser != null) {
            log("Primero cierra la sesión para esta prueba")
            return
        }
        log("SEG-10 Probando acceso SIN sesión ...")
        val dato = mapOf(
            "titulo" to "PRUEBA_SIN_SESION",
            "estado" to "Pendiente",
            "prioridad" to "Media",
            "version" to 1L,
            "modificadoPor" to "anonimo",
            "creadoPor" to "anonimo"
        )
        raiz.child("PRUEBA_SIN_SESION").setValue(dato)
            .addOnSuccessListener { log("⚠ SEG-10: ESCRITURA SIN SESIÓN ACEPTADA (falla de seguridad)") }
            .addOnFailureListener {
                log(
                    if (denegado(it)) "🔒 SEG-10: escritura sin sesión RECHAZADA por las reglas (esperado) → ${it.message}"
                    else "✖ SEG-10 escritura: ${it.message}"
                )
            }
        raiz.get()
            .addOnSuccessListener { log("⚠ SEG-10: LECTURA SIN SESIÓN ACEPTADA (falla de seguridad)") }
            .addOnFailureListener {
                log(
                    if (denegado(it)) "🔒 SEG-10: lectura sin sesión RECHAZADA por las reglas (esperado) → ${it.message}"
                    else "✖ SEG-10 lectura: ${it.message}"
                )
            }
    }

    override fun onCleared() {
        db.getReference(".info/connected").removeEventListener(escuchaConexion)
        desconectarIncidencias()
        auth.removeAuthStateListener(escuchaAuth)
        super.onCleared()
    }
}