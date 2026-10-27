package com.example.incidencias

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IncidenciasViewModel(app: Application) : AndroidViewModel(app) {

    // Si tu base está fuera de EE. UU., usa FirebaseDatabase.getInstance("https://...firebasedatabase.app")
    private val db = FirebaseDatabase.getInstance()
    private val raiz: DatabaseReference = db.getReference("incidencias")
    private val prefs = app.getSharedPreferences("config", Context.MODE_PRIVATE)

    private val datos = mutableMapOf<String, Incidencia>()
    private val pendientes = mutableSetOf<String>() // ids con escritura sin confirmar

    var dispositivo by mutableStateOf(prefs.getString("dispositivo", "A") ?: "A")
        private set
    var modo by mutableStateOf(ModoGuardado.CAMPOS)
        private set
    var online by mutableStateOf(false) // conexión real con el servidor de Firebase
        private set
    var incidencias by mutableStateOf<List<Incidencia>>(emptyList())
        private set
    var logs by mutableStateOf<List<String>>(emptyList())
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
            log("ERROR de escucha: ${error.message}")
        }
    }

    init {
        db.getReference(".info/connected").addValueEventListener(escuchaConexion)
        raiz.addChildEventListener(escuchaIncidencias)
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
            pendiente = false
        )
        datos[id] = inc
        refrescar()
        // Solo registramos como "Servidor" lo que NO es una escritura local en curso
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
        logs = (listOf("[$hora] $msg") + logs).take(60)
    }

    fun cambiarDispositivo(d: String) {
        dispositivo = d
        prefs.edit().putString("dispositivo", d).apply()
    }

    fun cambiarModo(m: ModoGuardado) {
        modo = m
    }

    /**
     * Realtime Database actualiza la lista local al instante (antes de que el servidor confirme).
     * Por eso marcamos el id como "pendiente" hasta que llega la confirmación real.
     * Si en 5 s no hay confirmación, lo informamos sin afirmar éxito.
     */
    private fun seguir(
        nombre: String,
        id: String,
        iniciar: ((Boolean, String?) -> Unit) -> Unit
    ) {
        var terminada = false
        var avisoTimeout = false

        pendientes.add(id)
        refrescar()

        iniciar { ok, error ->
            terminada = true
            pendientes.remove(id)
            refrescar()
            if (ok) {
                log(
                    if (avisoTimeout) "✔ $nombre: confirmado tardíamente al recuperar la red"
                    else "✔ $nombre: CONFIRMADO por el servidor"
                )
            } else {
                log("✖ $nombre: $error")
            }
        }

        viewModelScope.launch {
            delay(5000)
            if (!terminada) {
                avisoTimeout = true
                log("⚠ $nombre: SIN confirmación del servidor (¿sin conexión?). Escritura pendiente.")
            }
        }
    }

    // ---------- Operaciones ----------

    fun crear(titulo: String) {
        val id = raiz.push().key ?: return
        val nuevo = mapOf(
            "titulo" to titulo,
            "estado" to "Pendiente",
            "prioridad" to "Media",
            "version" to 1L,
            "modificadoPor" to dispositivo
        )
        seguir("Crear \"$titulo\"", id) { fin ->
            raiz.child(id).setValue(nuevo)
                .addOnSuccessListener { fin(true, null) }
                .addOnFailureListener { fin(false, it.message) }
        }
    }

    /**
     * COMPLETO:     setValue() reemplaza TODO el nodo (last write wins, pisa los campos del otro)
     * CAMPOS:       updateChildren() solo con los campos que cambiaron
     * TRANSACCION:  rechaza si alguien modificó el registro después de abrirlo
     */
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
                    "modificadoPor" to dispositivo
                )
                seguir(nombre, original.id) { fin ->
                    ref.setValue(completo)
                        .addOnSuccessListener { fin(true, null) }
                        .addOnFailureListener { fin(false, it.message) }
                }
            }

            ModoGuardado.CAMPOS -> {
                val cambios = mutableMapOf<String, Any>()
                if (titulo != original.titulo) cambios["titulo"] = titulo
                if (estado != original.estado) cambios["estado"] = estado
                if (prioridad != original.prioridad) cambios["prioridad"] = prioridad
                cambios["version"] = ServerValue.increment(1)
                cambios["modificadoPor"] = dispositivo
                seguir(nombre, original.id) { fin ->
                    ref.updateChildren(cambios)
                        .addOnSuccessListener { fin(true, null) }
                        .addOnFailureListener { fin(false, it.message) }
                }
            }

            ModoGuardado.TRANSACCION -> {
                seguir(nombre, original.id) { fin ->
                    ref.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(actual: MutableData): Transaction.Result {
                            // Sin dato local: dejamos que Firebase reintente con el valor real del servidor
                            if (actual.value == null) return Transaction.success(actual)

                            val versionActual =
                                (actual.child("version").value as? Number)?.toLong() ?: 1L
                            if (versionActual != original.version) return Transaction.abort()

                            actual.child("titulo").value = titulo
                            actual.child("estado").value = estado
                            actual.child("prioridad").value = prioridad
                            actual.child("version").value = original.version + 1
                            actual.child("modificadoPor").value = dispositivo
                            return Transaction.success(actual)
                        }

                        override fun onComplete(
                            error: DatabaseError?,
                            committed: Boolean,
                            snapshot: DataSnapshot?
                        ) {
                            when {
                                error != null -> fin(false, error.message)
                                !committed -> fin(
                                    false,
                                    "CONFLICTO: el registro cambió (tú editabas la versión " +
                                            "${original.version}). Recarga y reintenta."
                                )
                                snapshot?.exists() != true -> fin(false, "El registro ya no existe")
                                else -> fin(true, null)
                            }
                        }
                    })
                }
            }
        }
    }

    fun eliminar(original: Incidencia) {
        seguir("Eliminar \"${original.titulo}\"", original.id) { fin ->
            raiz.child(original.id).removeValue()
                .addOnSuccessListener { fin(true, null) }
                .addOnFailureListener { fin(false, it.message) }
        }
    }

    override fun onCleared() {
        db.getReference(".info/connected").removeEventListener(escuchaConexion)
        raiz.removeEventListener(escuchaIncidencias)
        super.onCleared()
    }
}