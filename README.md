# Driver Assistant

POC Android nativa para ajudar motoristas de aplicativos a analisar a rentabilidade de ofertas. O aplicativo funciona localmente e não aceita nem recusa corridas.

## Arquitetura

O projeto tem dois módulos Gradle com responsabilidades visíveis:

```text
driver-assistant/
├── app/        Android: telas, AccessibilityService, cartão e preferências locais
└── core/       Java puro: domínio, parsing, cálculo, classificação e testes unitários
```

`app` depende de `core`; `core` não depende do Android. Assim, regras de negócio podem ser testadas na JVM sem abrir uma Activity ou simular serviços do sistema.

```text
AccessibilityService → AccessibilityTreeReader → OfferParser
                                               → TripOffer
TripOffer → ProfitabilityCalculator → ClassificationService → OverlayController
```

### Pacotes

| Pacote | Responsabilidade |
|---|---|
| `com.driverassistant.accessibility` | Leitura limitada da árvore de acessibilidade |
| `com.driverassistant.overlay` | Cartão não clicável com `TYPE_ACCESSIBILITY_OVERLAY` |
| `com.driverassistant.parser` | Parsing tolerante de dinheiro, distâncias, tempos e nota |
| `com.driverassistant.domain` | Oferta, métricas, indicadores e classificações |
| `com.driverassistant.calculator` | Cálculos e estratégias do semáforo, sem Android |
| `com.driverassistant.config` | Limites e preferências locais |
| `com.driverassistant.location` | Ponto reservado para GPS futuro; sem permissão nesta POC |
| `com.driverassistant.service` | Ciclo de vida e eventos do serviço Android |
| `com.driverassistant.repository` | Contrato de repositório para persistência local futura |
| `com.driverassistant.ui` | Tela de diagnóstico e configuração |
| `com.driverassistant.util` | Deduplicação e logs de diagnóstico redigidos |

As configurações e diagnósticos usam `SharedPreferences` nesta etapa. O contrato `OfferRepository` prepara a evolução para Room/SQLite, mas não há banco instalado nem histórico de ofertas.

## Tecnologias

- Java 21 (JDK/toolchain e nível de linguagem)
- Android nativo com layouts XML e Material Components
- Android Gradle Plugin 9.5.0 e Gradle Wrapper 9.6.0
- `compileSdk 37`, `minSdk 29` (Android 10) e `targetSdk 36`
- JUnit 4 para testes locais do módulo `core`

## Pré-requisitos

- JDK 21 configurado em `JAVA_HOME`
- Android SDK com a plataforma API 37 e Android SDK Build Tools
- ADB para instalar o APK em aparelho físico
- Na primeira compilação, acesso aos repositórios Google Maven e Maven Central para baixar o Gradle e as dependências

Confira as ferramentas:

```powershell
java -version
adb version
```

## Compilar e testar

Na raiz do projeto, no PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:test
```

O APK de debug é gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalação e habilitação no celular

1. Ative a depuração USB no Android e conecte o aparelho.
2. Instale o APK:

   ```powershell
   $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
   & $adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Abra **Driver Assistant** e toque em **Abrir configurações de acessibilidade**.
4. Habilite **Monitoramento do Uber Driver**. O Android pode pedir confirmação adicional para um APK instalado fora da Play Store; isso depende da versão e do fabricante.
5. Volte ao app, ative o monitoramento e abra o Uber Driver.
6. O cartão usa o tipo de janela de acessibilidade e não captura toque, foco ou ações de aceite/recusa.
7. Use **Testar semáforo (simulado)** para verificar as classificações sem alterar a contagem de ofertas reais.

O serviço está limitado ao package `com.ubercab.driver`. Ele precisa estar habilitado pelo usuário nas configurações do Android para continuar ativo em segundo plano.

## Diagnóstico

Os eventos aparecem na tela principal e são escritos no Logcat com estas tags:

```powershell
adb logcat -s DA_ACCESSIBILITY DA_PARSER DA_OFFER DA_CALCULATOR DA_OVERLAY DA_CONFIG
```

**Exportar logs de diagnóstico** abre o seletor de compartilhamento do Android. A exportação contém contagens, códigos de evento e valores agregados; não inclui texto bruto da tela, nomes de passageiros ou endereços. Os dados transitórios usados pelo parser não são persistidos como histórico.

## Limitações conhecidas

- A árvore de acessibilidade do Uber Driver pode omitir campos ou mudar entre versões, aparelhos, regiões e tipos de oferta. Campos ausentes continuam indisponíveis; a POC não inventa valores.
- A detecção e o cartão ainda precisam ser validados em um aparelho físico com a versão do Uber Driver usada pelo motorista. Não se afirma que a integração esteja funcionando até essa validação.
- Os logs desta POC ajudam a diagnosticar eventos e parsing, mas não gravam o conteúdo integral capturado.
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
