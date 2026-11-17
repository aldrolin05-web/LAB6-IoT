package com.example.lab6;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import com.example.lab6.databinding.ActivityMainBinding;
import com.firebase.ui.auth.AuthUI;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements SesionAdapter.Acciones {

    private ActivityMainBinding binding;
    private final SesionAdapter sesionAdapter = new SesionAdapter(this);
    private ListenerRegistration listenerSesiones;
    private final Set<String> eliminacionesPendientes = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.rvSesiones.setAdapter(sesionAdapter);
        binding.btnAgregar.setOnClickListener(view ->
                startActivity(new Intent(this, FormularioSesionActivity.class)));
        binding.btnVerGraficos.setOnClickListener(view ->
                startActivity(new Intent(this, GraficosActivity.class)));

        binding.btnCerrarSesion.setOnClickListener(boton -> {
            boton.setEnabled(false);
            detenerEscucha();
            sesionAdapter.actualizarSesiones(Collections.emptyList());
            mostrarEstado(R.string.cerrando_sesion);

            AuthUI.getInstance().signOut(this)
                    .addOnSuccessListener(resultado -> abrirInicio())
                    .addOnFailureListener(error -> {
                        boton.setEnabled(true);
                        Toast.makeText(this, R.string.error_cerrar_sesion,
                                Toast.LENGTH_SHORT).show();
                        if (getLifecycle().getCurrentState().isAtLeast(
                                Lifecycle.State.STARTED)) {
                            escucharSesiones();
                        }
                    });
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding.btnCerrarSesion.isEnabled()) {
            escucharSesiones();
        }
    }

    @Override
    protected void onStop() {
        detenerEscucha();
        super.onStop();
    }

    private void escucharSesiones() {
        detenerEscucha();
        sesionAdapter.actualizarSesiones(Collections.emptyList());

        FirebaseUser usuario = FirebaseAuth.getInstance().getCurrentUser();
        if (usuario == null) {
            abrirInicio();
            return;
        }

        mostrarEstado(R.string.cargando_sesiones);
        String uid = usuario.getUid();

        listenerSesiones = FirebaseFirestore.getInstance()
                .collection("usuarios")
                .document(uid)
                .collection("sesiones")
                .orderBy("fecha", Query.Direction.DESCENDING)
                .addSnapshotListener((registros, error) -> {
                    FirebaseUser usuarioActual = FirebaseAuth.getInstance().getCurrentUser();
                    if (usuarioActual == null || !uid.equals(usuarioActual.getUid())) {
                        sesionAdapter.actualizarSesiones(Collections.emptyList());
                        mostrarEstado(R.string.sesion_finalizada);
                        return;
                    }

                    if (error != null) {
                        Log.e("Firestore", "No se pudieron cargar las sesiones de estudio", error);
                        sesionAdapter.actualizarSesiones(Collections.emptyList());
                        mostrarEstado(R.string.error_cargar_sesiones);
                        return;
                    }

                    if (registros == null) {
                        mostrarEstado(R.string.error_cargar_sesiones);
                        return;
                    }

                    List<SesionEstudio> sesiones;
                    try {
                        sesiones = new ArrayList<>();
                        for (com.google.firebase.firestore.DocumentSnapshot documento : registros) {
                            SesionEstudio sesion = documento.toObject(SesionEstudio.class);
                            if (sesion != null && sesion.esValida()) {
                                sesiones.add(sesion);
                            } else {
                                throw new IllegalArgumentException("Campos inválidos en " + documento.getId());
                            }
                        }
                    } catch (RuntimeException conversionError) {
                        Log.e("Firestore", "Formato de un registro de estudio inválido", conversionError);
                        sesionAdapter.actualizarSesiones(Collections.emptyList());
                        mostrarEstado(R.string.error_formato_sesiones);
                        return;
                    }

                    sesionAdapter.actualizarSesiones(sesiones);
                    if (sesiones.isEmpty()) {
                        mostrarEstado(R.string.sin_sesiones);
                    } else {
                        binding.txtEstadoSesiones.setVisibility(View.GONE);
                        binding.rvSesiones.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void detenerEscucha() {
        if (listenerSesiones != null) {
            listenerSesiones.remove();
            listenerSesiones = null;
        }
    }

    private void mostrarEstado(int mensaje) {
        binding.txtEstadoSesiones.setText(mensaje);
        binding.txtEstadoSesiones.setVisibility(View.VISIBLE);
        binding.rvSesiones.setVisibility(View.GONE);
    }

    private void abrirInicio() {
        Intent inicio = new Intent(this, CustomLoginActivity.class);
        inicio.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(inicio);
        finish();
    }

    @Override
    public void editar(SesionEstudio sesion) {
        Intent formulario = new Intent(this, FormularioSesionActivity.class);
        formulario.putExtra(FormularioSesionActivity.EXTRA_SESION_ID, sesion.getId());
        startActivity(formulario);
    }

    @Override
    public void eliminar(SesionEstudio sesion) {
        FirebaseUser usuario = FirebaseAuth.getInstance().getCurrentUser();
        if (usuario == null) {
            abrirInicio();
            return;
        }
        String uid = usuario.getUid();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eliminar_sesion)
                .setMessage(getString(R.string.confirmar_eliminar, sesion.getCurso()))
                .setNegativeButton(R.string.cancelar, null)
                .setPositiveButton(R.string.eliminar_sesion,
                        (dialogo, boton) -> eliminarConfirmado(sesion, uid))
                .show();
    }

    private void eliminarConfirmado(SesionEstudio sesion, String uid) {
        FirebaseUser usuario = FirebaseAuth.getInstance().getCurrentUser();
        if (usuario == null || !uid.equals(usuario.getUid())) {
            abrirInicio();
            return;
        }
        if (!eliminacionesPendientes.add(sesion.getId())) {
            return;
        }

        FirebaseFirestore.getInstance().collection("usuarios").document(usuario.getUid())
                .collection("sesiones").document(sesion.getId()).delete()
                .addOnSuccessListener(resultado -> {
                    eliminacionesPendientes.remove(sesion.getId());
                    Toast.makeText(this, R.string.sesion_eliminada, Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(error -> {
                    eliminacionesPendientes.remove(sesion.getId());
                    Log.e("Firestore", "Error al eliminar el registro", error);
                    Toast.makeText(this, R.string.error_eliminar_sesion, Toast.LENGTH_LONG).show();
                });
    }
}
