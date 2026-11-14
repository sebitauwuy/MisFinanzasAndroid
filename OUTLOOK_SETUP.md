# Conectar Outlook o Hotmail

Mis Finanzas utiliza un registro propio de Microsoft y MSAL para Android. En Ajustes, el usuario configura los últimos cuatro dígitos de su cuenta y la fecha y hora del saldo conciliado, inicia sesión en Microsoft y acepta el permiso de correo. La aplicación solicita Mail.Read delegado: permite leer el buzón completo del usuario, no modificar ni enviar mensajes. MSAL conserva la sesión y puede renovarla sin volver a pedir la contraseña. Los movimientos se presentan para revisión antes de cambiar saldos.

La conexión de código de dispositivo anterior se conserva para quienes ya la usaban. Al desconectar y conectar de nuevo se usa el flujo nuevo. Desconectar elimina la sesión local; para revocar el consentimiento hay que hacerlo desde la cuenta Microsoft.

El registro de producción admite cuentas personales de Microsoft. La redirección Android está asociada a la firma de distribución permanente. Los builds firmados con otra clave, incluidos builds de depuración, requieren una redirección y configuración MSAL correspondiente antes de probar el inicio de sesión. No se debe incluir un secreto de cliente en la aplicación.

La autorización debe probarse en un APK firmado en un teléfono, incluyendo consentimiento, consulta, renovación, reconexión y desconexión. La disponibilidad para otras cuentas y posibles avisos de publicador dependen de Microsoft; deben validarse antes de distribuir.

