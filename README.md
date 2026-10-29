# InkFlow — caderno digital para Android

Caderno de escrita à mão, anotação de PDFs e organização de estudos, feito para o **Vaio TL10 (Tab 10)** com a **caneta HYQ**. App nativo (Kotlin + Jetpack Compose), 100% local, sem conta, sem anúncios, sem telemetria e **sem permissão de internet**.

> **Status honesto desta entrega (v0.1.0):** o código foi escrito e revisado, mas **não foi compilado nem testado** no ambiente onde foi produzido — lá não havia SDK do Android e os repositórios do Google estavam bloqueados. Nenhum resultado de teste em hardware foi inventado. O primeiro passo é compilar no Android Studio e rodar a aba **Caneta** no tablet (ver "Primeiros passos").

---

## 1. Análise do hardware

| Item | O que se sabe (fichas técnicas públicas) | Como confirmar no aparelho |
|---|---|---|
| Sistema | Android 13 (API 33) | aba **Caneta → Aparelho** |
| Processador / RAM | Unisoc T616, 8 GB | idem |
| Tela | IPS 10,4", 2000×1200, 224 ppi, **60 Hz** | idem (lista os modos de atualização) |
| Digitalizador | **capacitivo comum**; nenhuma ficha cita digitalizador ativo (Wacom/EMR, USI, MPP) | lista de dispositivos de entrada: procura fonte `STYLUS` |
| Caneta HYQ | sem especificação oficial encontrada; canetas universais desse tipo são **capacitivas** | escrever na área de teste: azul = stylus, laranja = dedo |

**Consequência provável (a confirmar no teste):** o Android trata a caneta HYQ como **um dedo** (`TOOL_TYPE_FINGER`). Então:

- **não há pressão real nem inclinação** — o app não finge ter;
- **não dá para separar caneta de dedo pelo tipo de entrada** — por isso existe o modo *Qualquer toque escreve* (padrão no modo Automático quando nenhuma stylus é vista): **um toque escreve, dois dedos fazem zoom/rolagem**;
- **rejeição de palma é heurística**: tamanho do contato (calibrável), regras de múltiplos toques e o sinal `FLAG_CANCELED` do Android 13. Não é perfeita e o app não promete que seja;
- latência: 60 Hz limita o tempo entre quadros a ~16,7 ms; o app reduz o resto (entrada sem buffer, renderização incremental, predição curta).

Se o teste mostrar `STYLUS`, o modo Automático passa sozinho a usar a caneta para escrever e o dedo para navegar, e a pressão é usada se variar.

## 2. Primeiros passos

1. Instale o **Android Studio** (versão recente) e abra a pasta `InkFlow`.
2. Aguarde o *Gradle sync* (baixa AGP 8.7.3, Kotlin 2.0.21, Compose, Room).
3. No tablet: *Configurações → Sobre o tablet → toque 7× em "Número da versão"* → *Opções do desenvolvedor → Depuração USB*.
4. Conecte o cabo USB‑C e clique em **Run ▶** (ou use a linha de comando abaixo).
5. Abra a aba **Caneta**, escreva com a HYQ e com o dedo, faça a calibração de palma e toque em **Copiar relatório**.

Linha de comando (na pasta do projeto, com o SDK configurado em `local.properties` ou `ANDROID_HOME`):

```bash
./gradlew test                 # testes de lógica (JVM)
./gradlew assembleDebug        # gera app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest # testes no tablet (carga com 3000 traços, desfazer/refazer)
./gradlew assembleRelease      # APK otimizado (assinado com a chave de debug, para uso pessoal)
```

## 3. Arquitetura

Um módulo Gradle (`app`), organizado em camadas por pacote — separação equivalente aos módulos pedidos, sem o custo de build de multimódulo nesta fase:

```
com.inkflow
├── core/model        Modelo puro: Stroke (imutável), StrokeChange, codec binário versionado, modelos de papel
├── core/util         FloatList (lista sem boxing para o caminho quente)
├── ink/              MOTOR DE ESCRITA — sem nenhuma dependência de Compose
│   ├── input         Filtro 1€ (suavização)
│   ├── geometry      Hit-test, divisão por borracha, laço (funções puras, testadas na JVM)
│   ├── render        StrokePainter, TemplateRenderer (vetoriais)
│   ├── edit          PageDocument: traços da página + desfazer/refazer
│   └── view          InkCanvasView: entrada de stylus/toque, gestos, renderização
├── pdf               PdfSource (PdfRenderer do sistema), PdfImporter
├── data/db           Room: pastas, cadernos, páginas, traços
├── data/repo         NotebookRepository (única porta para os dados)
├── data/settings     Ajustes (SharedPreferences → StateFlow)
├── export            PdfExporter, BackupManager
├── thumbs            Miniaturas em segundo plano
└── ui                Compose: início/biblioteca/lixeira, editor, diagnóstico, ajustes
```

**Decisões que afetam latência, armazenamento e compatibilidade**

| Decisão | Motivo |
|---|---|
| Superfície de escrita é uma `View` nativa, não Compose | controle direto de `MotionEvent`, pontos históricos e custo por quadro |
| `requestUnbufferedDispatch()` (API 30+) | recebe amostras na taxa do digitalizador em vez de agrupadas por vsync |
| Filtro 1€ + curvas por pontos médios | suaviza tremor sem atrasar movimentos rápidos; o traço ao vivo e o salvo usam o mesmo algoritmo (sem "salto") |
| Predição linear de ~16 ms, desenhada só no quadro atual | reduz a sensação de atraso sem gravar pontos inventados |
| Renderização em **thread própria**, cache do tamanho da tela, front/back buffer | a página nunca é redesenhada inteira a cada movimento da caneta |
| Traço confirmado é "assado" direto no cache | custo de 1 traço, não da página |
| Zoom/rolagem transformam o cache e re-renderizam nítido ao final | gestos fluidos mesmo com milhares de traços |
| PDF em camada separada, só da área visível, na resolução da tela | memória controlada em PDFs grandes |
| `PdfRenderer` / `PdfDocument` nativos | zero bibliotecas de PDF → nenhuma restrição de licença |
| Room/SQLite em WAL, 1 transação por alteração, fila única de gravação | salvamento incremental, ordem garantida, sem bloquear a escrita |
| Coordenadas em **pontos tipográficos** (1/72") | independentes da tela; exportação para PDF é direta |
| Sem `fallbackToDestructiveMigration` | migrações nunca apagam cadernos |
| Sem WorkManager | miniaturas levam ms e são regeneradas sob demanda; menos dependências |
| Sem permissão de internet; `allowBackup=false` | privacidade: nada sai do tablet sem você exportar |

## 4. Estratégia de renderização (resumo)

1. **Camada estática** (papel, modelo, PDF, traços confirmados) → bitmap do tamanho da tela, renderizado na thread `InkFlow-Render` só para a região visível, com descarte de traços fora da tela.
2. **Overlay ao vivo** → traço em andamento (path incremental), traços recém-confirmados ainda não "assados", seleção em movimento, cursor da borracha, laço.
3. **Marca-texto** sempre por baixo da tinta (na camada estática), sem escurecer onde se sobrepõe ao próprio traço.
4. Espessura variável (lápis por velocidade; pressão quando existir) é desenhada numa camada com alfa único para não criar "contas" escuras.

## 5. Modelo de dados

- `folders(id, name, createdAt)`
- `notebooks(id, title, folderId, subject, coverColor, kind[NOTEBOOK|PDF], pdfFile, defaultTemplate, favorite, pinned, createdAt, updatedAt, openedAt, deletedAt, lastPageIndex, formatVersion)`
- `pages(id, notebookId→CASCADE, position, template, widthPt, heightPt, pdfPageIndex, title, favorite, updatedAt)`
- `strokes(id, pageId→CASCADE, kind, color ARGB, width pt, z, createdAt, points BLOB)`

`points` usa o formato binário **versionado** do `StrokeCodec` (v1: versão + quantidade + trios `x, y, w` em float32). `w` é o multiplicador de espessura. Anotações de PDF são traços comuns em páginas que apontam para `pdfPageIndex` — o PDF original nunca é alterado.

Arquivos (armazenamento privado do app): `databases/inkflow.db`, `files/pdfs/`, `files/thumbs/`. Backup: `.zip` com `manifest.json` versionado + banco + PDFs.

## 6. O que a v0.1.0 faz

**Escrita:** caneta, lápis (espessura por velocidade), marca-texto, cores, espessura, opacidade, favoritas (presets), borracha de traço inteiro e por área com cursor, desfazer/refazer (300 passos por página), laço para selecionar/mover/duplicar/recolorir/excluir, zoom (0,5×–10×) e rolagem com dois dedos, ferramenta "mover página", toque com 2 dedos = desfazer, 3 dedos = refazer, atalhos de teclado (a capa-teclado do TL10).

**Cadernos:** criar, renomear, matéria, pasta, cor de capa, favoritar, fixar, lixeira com restaurar e excluir definitivo, busca por título/matéria, ordenação, páginas ilimitadas (adicionar, duplicar, reordenar, excluir), miniaturas, 10 modelos de papel (branca, pautada, quadriculada, pontilhada, Cornell, semanal, mensal, tarefas, matemática, desenho), troca de modelo sem apagar traços.

**PDF:** importar pelo seletor do sistema, navegar, escrever/grifar/desenhar por cima, zoom, anotações salvas e reabertas, exportar PDF anotado (traços vetoriais) e o original intacto, excluir com confirmação.

**Confiabilidade:** cada traço é gravado ao levantar a caneta, em transação; ao fechar à força perde-se no máximo o traço em andamento. Backup/restauração completos; restauração é aplicada no próximo início, antes de abrir o banco.

**Diagnóstico:** tipo de entrada, pressão mín./máx., tamanho do contato, inclinação, orientação, botões, hover, toques cancelados, taxa de amostragem, dispositivos de entrada e seus eixos, RAM, tela, Hz; calibração de palma; relatório copiável.

## 7. Plano de testes

| # | Teste | Como | Status |
|---|---|---|---|
| T1 | Codec, geometria, laço, filtro | `./gradlew test` (`CoreLogicTest`) | escrito, **não executado aqui** |
| T2 | 3000 traços: gravar, recarregar, renderizar | `connectedAndroidTest` (`StressTest`), tempos no Logcat `InkFlowStress` | escrito, **não executado** |
| T3 | Desfazer/refazer exato | `StressTest.undoRedo_restoresExactState` | escrito, **não executado** |
| T4 | Reconhecimento da HYQ | aba Caneta → relatório | a fazer no tablet |
| T5 | Escrita prolongada (10 min seguidos, página cheia) | observar travadas; Perfetto/"Profile GPU rendering" | a fazer |
| T6 | Latência percebida | filmar em câmera lenta (240 fps) a ponta da caneta e o traço; contar quadros de distância | a fazer |
| T7 | Palma apoiada | escrever 1 página com a mão apoiada, contar marcas indesejadas, antes/depois de calibrar | a fazer |
| T8 | Fechamento inesperado | escrever, forçar parada nas Configurações, reabrir | a fazer |
| T9 | Reinício do tablet | escrever, reiniciar, conferir | a fazer |
| T10 | PDF grande (300+ páginas, com imagens) | importar, navegar, anotar, exportar; observar memória | a fazer |
| T11 | Dedo × caneta em cada modo de toque | Ajustes → Toque e caneta | a fazer |
| T12 | Backup e restauração | exportar, apagar dados, restaurar | a fazer |

Registre os resultados reais (inclusive falhas) — eles definem os ajustes da próxima versão.

## 8. Limitações conhecidas

- **Não compilado/testado ainda** (ver topo). Pode haver erros de compilação pontuais; me envie a mensagem de erro do Android Studio que eu corrijo.
- Caneta capacitiva ⇒ sem pressão/inclinação reais; rejeição de palma heurística; não há como "somente caneta" de verdade.
- 60 Hz: o atraso mínimo de exibição é maior que em telas de 120 Hz; a predição só compensa parte disso.
- Ainda **não usa renderização *front-buffered*** (`androidx.graphics`) — candidata para a próxima versão, após medir a latência real.
- PDF anotado exportado: a página original entra como imagem (≈144 dpi; ≈108 dpi acima de 80 páginas). O texto do PDF deixa de ser selecionável nessa versão — o original continua exportável intacto. Futuro: sobrepor os traços vetoriais ao PDF original (ex.: PdfBox‑Android, Apache‑2.0).
- Exportar PDFs muito longos consome memória (o `PdfDocument` do Android mantém as páginas até gravar).
- Histórico de desfazer é por página e em memória (some ao trocar de página ou fechar o app; o conteúdo salvo não).
- Busca **não** procura no conteúdo manuscrito — não há reconhecimento de escrita nesta versão.
- Ainda não implementados: formas geométricas, texto digitado, imagens, notas adesivas, índice/títulos, modelos de resumo/mapa mental, páginas favoritas, OCR, capas com imagem.
- Uma página por vez (sem rolagem contínua entre páginas).
- Os textos da interface estão no código (não em `strings.xml`); suficiente para uso pessoal, a extrair se o app for traduzido.

## 9. Próximas fases sugeridas

1. **Correções da primeira compilação + resultados do diagnóstico** (ajustar suavização, predição e limiar de palma com dados reais).
2. Ferramentas: formas (com reconhecimento de linha/círculo ao segurar), texto digitado, imagens.
3. Estudos: índice por títulos, páginas favoritas, modelos de resumo e mapa mental, revisão semanal, registro de dúvidas.
4. PDF: exportação mantendo o texto original; marcadores; busca no texto do PDF.
5. Desempenho: renderização *front-buffered*, cache por blocos (tiles) para zoom alto, rolagem contínua.
6. Reconhecimento de escrita opcional (ML Kit Digital Ink, offline após baixar o modelo) para busca no manuscrito.
