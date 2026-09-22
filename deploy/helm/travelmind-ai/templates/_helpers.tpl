{{- define "travelmind-ai.name" -}}travelmind-ai{{- end }}
{{- define "travelmind-ai.fullname" -}}{{ .Release.Name }}-travelmind-ai{{- end }}
{{- define "travelmind-ai.labels" -}}
app.kubernetes.io/name: {{ include "travelmind-ai.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "travelmind-ai.pod" -}}
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
imagePullPolicy: {{ .Values.image.pullPolicy }}
ports:
  - {name: http, containerPort: 8123}
  - {name: management, containerPort: 9090}
envFrom:
  - configMapRef: {name: {{ include "travelmind-ai.fullname" . }}}
  - secretRef: {name: {{ .Values.existingSecret }}}
startupProbe:
  httpGet: {path: /actuator/health/liveness, port: management}
  failureThreshold: 30
  periodSeconds: 10
livenessProbe:
  httpGet: {path: /actuator/health/liveness, port: management}
  failureThreshold: 3
  periodSeconds: 15
readinessProbe:
  httpGet: {path: /actuator/health/readiness, port: management}
  failureThreshold: 3
  periodSeconds: 10
lifecycle:
  preStop:
    exec: {command: ["sh", "-c", "sleep 10"]}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  runAsNonRoot: true
  capabilities: {drop: ["ALL"]}
volumeMounts:
  - {name: tmp, mountPath: /tmp}
{{- end }}
