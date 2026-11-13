package cl.personal.finanzas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

@Entity(tableName = "movements")
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String,
    val description: String,
    val amountClp: Long,
    val date: String,
    val category: String = "General"
)

@Dao
interface MovementDao {
    @Query("SELECT * FROM movements ORDER BY date DESC, id DESC")
    fun observe(): Flow<List<Movement>>
    @Insert suspend fun insert(movement: Movement)
    @Delete suspend fun delete(movement: Movement)
}

@Database(entities = [Movement::class], version = 1, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun movements(): MovementDao
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finanzas.db").build()
        setContent { FinanceScreen(db.movements()) }
    }
}

@Composable
fun FinanceScreen(dao: MovementDao) {
    val scope = rememberCoroutineScope()
    val movements by dao.observe().collectAsState(initial = emptyList())
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("Banco de Chile") }
    var category by remember { mutableStateOf("General") }
    var expense by remember { mutableStateOf(true) }
    val money = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }
    val total = movements.sumOf { it.amountClp }
    MaterialTheme {
        Scaffold(topBar = { Surface(tonalElevation = 3.dp) { Text("Mis Finanzas · CLP", modifier = Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleLarge) } }) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("Balance registrado: ${money.format(total)}", style = MaterialTheme.typography.headlineSmall)
                    Text("Suma de movimientos registrados; no es tu saldo bancario real.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) }, label = { Text("Monto en pesos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text("Cuenta")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Banco de Chile", "CMR", "Efectivo").forEach { name -> FilterChip(selected = account == name, onClick = { account = name }, label = { Text(name) }) }
                    }
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Categoría") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = expense, onClick = { expense = true }, label = { Text("Gasto") })
                        FilterChip(selected = !expense, onClick = { expense = false }, label = { Text("Ingreso") })
                    }
                    Button(onClick = {
                        val value = amount.toLongOrNull()
                        if (description.isNotBlank() && value != null && value > 0) {
                            scope.launch { dao.insert(Movement(account = account, description = description.trim(), amountClp = if (expense) -value else value, date = LocalDate.now().toString(), category = category.trim())) }
                            description = ""; amount = ""
                        }
                    }, enabled = description.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0L, modifier = Modifier.fillMaxWidth()) { Text("Guardar movimiento") }
                    HorizontalDivider()
                    Text("Movimientos", style = MaterialTheme.typography.titleLarge)
                }
                items(movements, key = { it.id }) { movement ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(movement.description, style = MaterialTheme.typography.titleMedium)
                                Text("${movement.account} · ${movement.category} · ${movement.date}", style = MaterialTheme.typography.bodySmall)
                                Text(money.format(movement.amountClp))
                            }
                            TextButton(onClick = { scope.launch { dao.delete(movement) } }) { Text("Eliminar") }
                        }
                    }
                }
            }
        }
    }
}
