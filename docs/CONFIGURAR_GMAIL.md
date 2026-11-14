# Activar Gmail en Mis Finanzas (Android)

La selección de una cuenta Google **no concede por sí sola** acceso a Gmail.
Mis Finanzas solicita el scope `https://www.googleapis.com/auth/gmail.readonly`,
que Google clasifica como **restringido**. Para la primera prueba se necesita
configurar OAuth en Google Cloud.

## Preparación

1. Abre https://console.cloud.google.com y selecciona o crea un proyecto.
2. En APIs y servicios > Biblioteca, habilita **Gmail API**.
3. En Google Auth Platform > Branding, completa los datos de la aplicación.
4. En Audience, elige **External > Testing** (para pruebas privadas) y agrega
   **la dirección Gmail exacta** que usarás a los usuarios de prueba.
5. En Data Access, agrega el scope
   `https://www.googleapis.com/auth/gmail.readonly`.
6. En Google Auth Platform > Clients > Create Client, selecciona **Android**:
   - Nombre del paquete: `cl.personal.finanzas`.
   - Huella SHA-1: **la de la APK RELEASE firmada**, no la de depuración.
7. Para obtener la huella SHA-1, abre la última ejecución exitosa de
   `Compilar APK Android` en GitHub Actions para la rama `main`.
   En el job **build**, busca el paso
   `Mostrar SHA-1 público de la APK para Google OAuth`.
   Ese paso imprime únicamente la huella pública, **ninguna clave privada**.
8. Instala esa misma APK firmada y abre
   **Ajustes > Correos conectados > Conectar Gmail**.
9. Elige tu cuenta y concede explícitamente el permiso de lectura solicitado.
   En modo prueba Google puede mostrar una advertencia de aplicación no
   verificada: continúa solo si confías en tu propio proyecto.

## Si vuelve al menú sin conectar

- Revisa que el Gmail escogido figure en **usuarios de prueba**.
- Confirma que el cliente sea **Android**, con paquete y SHA-1 exactos.
- Comprueba que **Gmail API** esté habilitada en el mismo proyecto.
- Anota el **código de error** que aparece en Mis Finanzas (nueva versión);
  no compartas contraseñas, códigos OAuth ni tokens.
- Si Google Cloud se acaba de configurar, algunos cambios pueden tardar
  en propagarse.

No se registran movimientos sin revisión previa y no se modifica la
conexión existente de Outlook.

Documentación oficial:
- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/gmail/api/auth/scopes
- https://developers.google.com/workspace/guides/create-credentials
- https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
