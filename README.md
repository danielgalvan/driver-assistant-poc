# Driver Assistant

POC Android nativa para ajudar motoristas de aplicativos a analisar a rentabilidade de ofertas. O aplicativo funciona localmente e não aceita nem recusa corridas. As estruturas e ofertas usadas pelos testes são simuladas; a leitura real da Uber ainda depende de validação física.

## Arquitetura

O projeto tem dois módulos Gradle com responsabilidades visíveis:

```text
driver-assistant/
├── app/        Android: telas, AccessibilityService, cartão e preferências locais
└── core/       Java puro: domínio, parsing, cálculo, classificação e testes unitários
```

`app` depende de `core`; `core` não depende do Android. Assim, regras de negócio podem ser testadas na JVM sem abrir uma Activity ou simular serviços do sistema.

```text
AccessibilityService event
  ├─ event source → bounded immutable snapshot → coalescing queue ─┐
  └─ Uber-window poll (350 ms, adaptive backoff) ───────────────────┤
                                                                   ▼
                                                            OfferParser
                                                                 → TripOffer
TripOffer → OfferLifecycleMachine → ProfitabilityCalculator → ClassificationService
                                                        ↘ OverlayController
```

### Pacotes

| Pacote | Responsabilidade |
|---|---|
| `com.driverassistant.accessibility` | Snapshot limitado com texto, descrição, ID, classe, ordem e hierarquia |
| `com.driverassistant.overlay` | Cartão não clicável, não focalizável e com prazo por sessão |
| `com.driverassistant.parser` | Parsing contextual de nós, valores localizados, distâncias, tempos e nota |
| `com.driverassistant.domain` | Oferta, métricas, indicadores e classificações |
| `com.driverassistant.calculator` | Cálculos e estratégias do semáforo, sem Android |
| `com.driverassistant.lifecycle` | Máquina de estados de ofertas e política de duração do cartão, sem Android |
| `com.driverassistant.config` | Limites e preferências locais |
| `com.driverassistant.location` | Ponto reservado para GPS futuro; sem permissão nesta POC |
| `com.driverassistant.service` | Ciclo de vida e eventos do serviço Android |
| `com.driverassistant.repository` | Contrato de repositório para persistência local futura |
| `com.driverassistant.ui` | Tela de diagnóstico e configuração |
| `com.driverassistant.util` | Logs locais redigidos, agrupados e exportáveis |

As configurações usam `SharedPreferences`; o diagnóstico detalhado usa um arquivo JSON Lines privado do app e uma fila de gravação em segundo plano. O contrato `OfferRepository` prepara a evolução para Room/SQLite, mas não há banco instalado nem histórico de corridas.

## Tecnologias

- Java 21 (JDK/toolchain e nível de linguagem)
- Android nativo com layouts XML e Material Components
- Android Gradle Plugin 9.5.0 e Gradle Wrapper 9.6.0
- `compileSdk 37`, `minSdk 29` (Android 10) e `targetSdk 36`
- JUnit 4 para testes locais do módulo `core`

## Pré-requisitos

- JDK 21 configurado em `JAVA_HOME`
- Android SDK com a plataforma API 37.0 Preview e Android SDK Build Tools 37
- ADB para instalar o APK em aparelho físico
- Na primeira compilação, acesso aos repositórios Google Maven e Maven Central para baixar o Gradle e as dependências

Combinação usada nesta revisão: Java 21, Gradle Wrapper 9.6.0, Android Gradle Plugin 9.5.0, `compileSdk 37`, `targetSdk 36` e `minSdk 29`. O pacote do Android 17 Preview para API 37.0 tem o identificador `platforms;android-37.0` (não `platforms;android-37`); a CI o instala pelo canal Canary (`sdkmanager --channel=3`). O log da execução confirma que as licenças foram aceitas e que o erro era o identificador inexistente do pacote. O AGP 9.5.0 suporta API 37 ([notas do AGP 9.5.0](https://developer.android.com/build/releases/agp-9-5-0-release-notes)); o SDK Manager documenta `--channel=3` para incluir pacotes Canary ([documentação do sdkmanager](https://developer.android.com/tools/sdkmanager)); Gradle suporta execução em Java 21 ([compatibilidade do Gradle](https://docs.gradle.org/current/userguide/compatibility.html)).

Para instalar os pacotes manualmente no Windows, use o SDK Manager do Android Studio ou execute no terminal em que `sdkmanager` está no `PATH`:

```powershell
sdkmanager --channel=3 "platforms;android-37.0" "build-tools;37.0.0"
```

Confira as ferramentas:

```powershell
java -version
adb version
```

## Compilar e testar

Na raiz do projeto, no PowerShell:

```powershell
.\gradlew.bat clean
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

O APK de debug é gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalação e habilitação no celular

1. Ative a depuração USB no Android, aceite a autorização RSA no aparelho e confira a conexão:

   ```powershell
   $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
   & $adb devices -l
   ```

   O dispositivo deve aparecer com o estado `device`.
2. Instale o APK:

   ```powershell
   $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
   & $adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Abra **Driver Assistant** e toque em **Abrir configurações de acessibilidade**.
4. Habilite **Monitoramento do Uber Driver**. O Android pode pedir confirmação adicional para um APK instalado fora da Play Store; isso depende da versão e do fabricante.
5. Volte ao app e ative o monitoramento. Abra o Uber Driver.
6. O cartão usa `TYPE_ACCESSIBILITY_OVERLAY`, com `FLAG_NOT_TOUCHABLE` e `FLAG_NOT_FOCUSABLE`; ele não executa ações de aceite ou recusa.
7. Use **Testar semáforo (simulado)** para conferir as regras na tela do Driver Assistant.
8. Para conferir o cartão Android real, habilite o serviço e o monitoramento, abra **Testar cartão flutuante** e escolha um cenário. A simulação não incrementa contadores reais; o cartão mostra “SIMULAÇÃO”. Não há cenário disponível enquanto uma oferta real estiver ativa.
9. Teste a mudança de app, a expiração configurada e o desligamento do monitoramento. Compare os valores do cartão com a tela do Uber Driver sem interagir com os botões de aceite ou recusa.

O serviço recebe metadados de eventos de acessibilidade para acompanhar mudanças de janela. Ele só percorre e analisa a árvore quando a raiz pertence ao package `com.ubercab.driver`; para outros apps, não lê texto, descrições ou componentes filhos. Essa coleta de metadados permite registrar uma janela externa, como a do Rota Pro, sem misturar o conteúdo da sobreposição à oferta Uber. O serviço não exige desativar outros serviços de acessibilidade. A coexistência real ainda precisa ser confirmada no aparelho com Uber Driver e Rota Pro instalados.

## Diagnóstico

Os eventos resumidos aparecem na tela principal e são escritos no Logcat com estas tags:

```powershell
adb logcat -s DA_ACCESSIBILITY DA_PARSER DA_OFFER DA_CALCULATOR DA_OVERLAY DA_CONFIG
```

**Exportar logs de diagnóstico** continua oferecendo o resumo curto pelo seletor de compartilhamento. Para a investigação da regressão, use a nova captura detalhada temporária na tela inicial:

1. Confirme na tela inicial que o serviço de acessibilidade está conectado. **Diagnóstico**, **Monitoramento** e **Cartão configurado** aparecem como estados separados. Toque em **Iniciar diagnóstico**. Se o monitoramento estiver desligado, o app avisa que a captura e o parser rodam, mas a máquina de estados e o cartão não.
2. Deixe o Driver Assistant em segundo plano e use o Uber Driver normalmente. A captura permanece no aparelho, sem USB, ADB, Android Studio, Codex ou internet.
3. Depois do teste, volte ao Driver Assistant e toque em **Encerrar diagnóstico**.
4. Toque em **Exportar sessão ZIP (JSON)**. O arquivo fica em `Downloads/Driver Assistant/driver-assistant-diagnostic-AAAAmmdd-HHmmss.zip`; dentro dele, `diagnostics.json` contém eventos cronológicos e snapshots anonimizados. Cada registro tem `session_id` para distinguir sessões preservadas.
5. **Limite de armazenamento** oferece 10, 25, 50 ou 100 MB; começa em 50 MB. Cada sessão encerra automaticamente após 8 horas ou quando atinge o limite. Registros são gravados progressivamente em armazenamento privado; sessões encerradas ficam disponíveis por até 7 dias ou até **Limpar dados de diagnóstico**.

A captura detalhada fica desativada até o usuário iniciá-la. Snapshots guardam ordem, hierarquia, classe, ID, visibilidade e textos relevantes anonimizados do Uber Driver. Valores de nota, preço e medida fragmentados são mantidos somente quando os elementos próximos fornecem contexto. O conteúdo das árvores de outros apps nunca é lido ou salvo; eventos externos são resumidos por pacote/tipo e metadados da janela. A fila é limitada a oito snapshots, coalesce o trabalho repetido da mesma janela e protege snapshots com sinais de oferta de eventos comuns do mapa. Uma subárvore capturada no callback não fica esperando para consultar a árvore viva depois. Parsing, máquina de estados e escrita do diagnóstico rodam na thread de análise.

Com o monitoramento ativo, uma rotina consulta as janelas pertencentes ao pacote Uber mesmo antes de reconhecer a primeira oferta. Ela começa em 350 ms, recua para 1 s após uma leitura estável e para 1,5 s quando nenhuma janela Uber está acessível. Um evento novo ou sinal de oferta restaura a frequência rápida. O ciclo não acumula consultas simultâneas. Eventos capturam no callback um contexto limitado a 64 nós; o polling limita a leitura completa a 220 nós por janela e examina até três janelas Uber, começando pelas ativas/focadas. A maior árvore do diagnóstico anterior tinha 194 nós. Uma árvore truncada pode ser analisada para uma oferta presente, mas não serve sozinha para declarar seu desaparecimento. Rejeições de subárvores parciais de eventos também não encerram uma oferta.

### Evidência da sessão de 27/11 e causa confirmada

O arquivo `driver-assistant-diagnostic-20261127-session.json` contém 306 registros, 178 eventos de acessibilidade, 27 snapshots e 26 resultados do parser. Todas as 26 execuções indicaram `monitoring_enabled=true`; nenhuma foi aceita. As rejeições foram 15 por contexto de mapa, 10 por falta de ação/valor rotulado e 1 por mapa de oportunidades offline. Houve dois registros de estouro da fila; o atraso registrado chegou a 2.768 ms, a leitura da árvore a 1.083 ms e o processamento total a 3.629 ms. O usuário confirmou que duas ofertas reais foram exibidas durante a sessão; portanto, esses dados mostram que o app não as reconheceu, não que elas não existiram.

Na implementação anterior, a fila guardava apenas metadados do evento. A thread de análise consultava a janela viva depois, podendo ler uma tela posterior; enquanto isso, eventos de conteúdo acumulavam e eram descartados. O polling também só começava depois de uma oferta já reconhecida. Esses dois gargalos estão confirmados no código e nos tempos do diagnóstico. Os IDs `ub_driver_offers_browse_*` e `ub__driver_job_offers_pill_*` apareceram visíveis em alguns snapshots e invisíveis em outros. Eles identificam a área de ofertas/oportunidades, mas não provam por si só que um cartão de corrida esteja presente; são usados apenas para preservar/priorizar a captura.

As regras do `OfferParser` e os limites do semáforo não foram alterados. A validação no aparelho ainda precisa confirmar se o evento da oferta real expõe dados suficientes na subárvore capturada e se o polling de 220 nós termina rápido o bastante no Xiaomi. A coleta nova registra `snapshot_origin`, idade do evento na recepção, tempos monotônicos de captura/fila/leitura/parsing/escrita e o resultado de cada janela; isso permitirá localizar a etapa da próxima oferta sem gravar conteúdo de outros apps.

O modo **Diagnóstico detalhado da estrutura acessível** nas configurações continua separado e desligado por padrão. Ele grava o resumo estrutural já existente; a sessão temporária acima registra os dados necessários para a investigação e exporta JSON.

A tela separa o serviço conectado, a permissão habilitada, o monitoramento, se a Uber já foi vista, se está em primeiro plano agora (quando a janela ativa permite determinar), se há oferta ativa e se o cartão está visível. “Último evento” é histórico e não é apresentado como estado atual.

Os limites iniciais continuam sendo R$/km vermelho abaixo de `1,40` e verde a partir de `1,80`; R$/hora vermelho abaixo de `30,00` e verde a partir de `40,00`; nota vermelha abaixo de `4,30` e verde a partir de `4,90`. Indicadores, estratégia, duração, posição e campos visíveis continuam configuráveis e persistidos localmente.

## GitHub Actions

O workflow `.github/workflows/android-ci.yml` é executado em pushes e pull requests para `main` e `develop`. Ele valida o Gradle Wrapper, configura Java 21 e Android API 37, executa compilação do `core`, testes, Android Lint e APK debug e publica o APK e relatórios como artefatos da execução. Não há publicação na Play Store.

## Limitações conhecidas

- A árvore de acessibilidade do Uber Driver pode omitir campos ou mudar entre versões, aparelhos, regiões e tipos de oferta. Campos ausentes continuam indisponíveis; a POC não inventa valores.
- Nenhum layout real da Uber é presumido. Os exemplos unitários são fixtures simuladas; a detecção e o cartão ainda precisam ser validados em aparelho físico com a versão do Uber Driver usada pelo motorista. Não se afirma que a integração real esteja funcionando antes dessa validação.
- A sessão temporária preserva uma árvore anonimizada do Uber Driver. A anonimização depende dos padrões reconhecíveis de nome/endereço/telefone; revise o ZIP antes de compartilhar. O arquivo permanece local até o usuário exportá-lo.
- Não foi validada fisicamente nesta entrega a coexistência com o Rota Pro nem a leitura de uma oferta real. O próximo teste deve iniciar o diagnóstico, usar ambos os apps com o Uber Driver em primeiro plano, encerrar e exportar a sessão; os eventos `ACTIVE_WINDOW_NOT_UBER`, `PARSER_RESULT`, `LIFECYCLE_RESULT` e `OVERLAY_*` mostrarão onde o fluxo parou.
- A POC não usa backend, rede, autenticação, Room/SQLite, GPS, localização, notificações, acessibilidade para aceitar/recusar, root ou APIs privadas da Uber.
- Não há detecção de corrida aceita, histórico, jornadas, veículos, combustível, manutenção, backup ou integração com a 99.

### Acessibilidade e Google Play

O serviço requer ativação explícita pelo usuário e deve ser explicado com transparência antes da ativação. A política da Google Play exige declaração e divulgação adequadas para o uso da Accessibility API e restringe usos que iniciem, planejem ou executem ações de forma autônoma. Esta POC limita-se a leitura para análise e exibição; não toma decisões pelo motorista. A aprovação para publicação depende de revisão e das políticas vigentes.

Referências oficiais: [Accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service), [AccessibilityService.TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#TYPE_ACCESSIBILITY_OVERLAY) e [Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491).

## Roadmap (fora desta fase)

- Identificação automática de corridas aceitas, embarque, finalização e cancelamento
- Histórico de corridas e persistência com Room/SQLite
- Controle de jornadas de trabalho
- Monitoramento GPS em segundo plano
- Quilometragem total da jornada, até o embarque, com passageiro e sem corrida ativa
- Cadastro de veículos
- Controle de abastecimentos, consumo médio e custo de combustível por km
- Manutenção por quilometragem e provisionamento de custos
- Dashboard financeiro
- Backup e restauração
- Integração com a 99

O roadmap não inclui cálculo de depreciação do veículo.
