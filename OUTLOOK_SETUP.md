# Mis Finanzas v1.1 — Outlook/Hotmail (beta experimental)

## Estado
La aplicación puede conectarse a Microsoft Graph **solo después** de configurar un registro de aplicación Microsoft. No se reutiliza la conexión de ChatGPT; son autorizaciones independientes.

Esta versión usa el flujo OAuth 2.0 **device code** como prototipo, con autorización fuera de la aplicación. Microsoft recomienda MSAL y autorización adecuada para apps móviles para el lanzamiento público; antes de distribuir masivamente debemos migrar a MSAL/autorización con PKCE.

## Registro de la app (dueño del proyecto)

1. Entra a https://entra.microsoft.com/ y abre **App registrations** → **New registration**. Necesitarás una cuenta con permisos para registrar aplicaciones.
2. Nombre: `Mis Finanzas`. Tipo de cuenta compatible: **Personal Microsoft accounts only**, o la opción que incluya cuentas personales Microsoft.
3. En **Authentication**, activa **Allow public client flows = Yes** para permitir el device code flow. No generes ni introduzcas un client secret dentro del APK.
4. En **API permissions** incorpora **Microsoft Graph / Delegated / Mail.Read**. Si Microsoft exige consentimiento, autorízalo en su pantalla oficial. La app solicita también `offline_access` para renovar la autorización.
5. Copia **Application (client) ID** (GUID público). En Mis Finanzas → Ajustes → Outlook / Hotmail introduce ese ID. **Nunca copies secretos, contraseñas ni tokens**.
6. Indica solo los últimos cuatro dígitos de tu cuenta Banco de Chile para relacionar correos de cargo con la cuenta correcta.
7. Introduce **la fecha del último saldo ya conciliado**, por ejemplo el día hasta el cual ya cargaste la cartola. Solo se proponen operaciones **posteriores** a esa fecha; no se modifica el saldo inicial.
8. Presiona **Conectar Outlook / Hotmail**; visita la URL oficial de Microsoft y escribe el código temporal mostrado. Los tokens se almacenan cifrados localmente usando Android Keystore.

## Qué hace

- Elige los correos recibidos los últimos 30 días (hasta 300 por sesión); se descargan a memoria para filtrar los avisos.
- Solo reconoce, por ahora, remitentes `enviodigital@bancochile.cl` y `serviciodetransferencias@bancochile.cl`, con plantillas de cargo en cuenta y comprobantes de transferencias.
- Extrae fecha, comercio, tipo de movimiento y monto; valida los 4 dígitos de cuenta cuando aparecen.
- Presenta **movimientos pendientes**; el usuario debe confirmar explícitamente para afectar sus saldos.
- Compara cuenta, monto y fecha aproximada con los movimientos existentes y marca coincidencias como posibles duplicados (puede haber falsos positivos).
- Conserva solamente los datos mínimos del movimiento y el hash del ID del correo, no los mensajes completos.
- Consulta al **abrir la app desbloqueada**, si la opción está activa, o manualmente con **Buscar avisos ahora**. No ejecuta sincronización permanente en segundo plano.
- Al pulsar **Desconectar** borra de la app las credenciales locales cifradas; el usuario puede revocar acceso desde su cuenta Microsoft. La bandeja de movimientos analizados queda localmente, para evitar perder el historial.
- Los archivos de respaldo contienen los movimientos revisados pero **no** las credenciales OAuth.

## Limitaciones y pendientes

- La dirección del remitente por sí sola no certifica autenticidad criptográfica. No aceptar avisos sin revisión; contrastar con cartolas.
- La extracción de transferencias es conservadora, pero la dirección puede ser ambigua. Revisar las transferencias entre cuentas propias y pagos de CMR antes de registrar.
- El acceso Microsoft Graph **Mail.Read** permite leer todo el buzón, aunque la aplicación procese solo mensajes bancarios recientes; no existe permiso para solo un remitente. Debe informarse claramente.
- La app no lee saldos reales en línea y no puede transferir fondos.
- No utiliza servicios propios ni transmite correos a un backend nuestro. Las solicitudes HTTPS van de la app a Microsoft.
- El flujo de registro inicial no fue probado con una cuenta Microsoft desde un APK instalado hasta completar el registro de aplicación y el build verde.
- Para un lanzamiento público, usar MSAL y configuración del client ID central, agregar sincronización incremental (delta) y evaluación de permisos/seguridad adicional.

Referencia:
- https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code
- https://learn.microsoft.com/en-us/graph/api/user-list-messages?view=graph-rest-1.0
