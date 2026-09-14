<script setup lang="ts">
withDefaults(
  defineProps<{
    /** Maven artifactId，默认核心库 jkit */
    artifact?: string
  }>(),
  { artifact: 'jkit' },
)
</script>

<template>
  <a
    class="maven-badge"
    :href="`https://central.sonatype.com/artifact/com.alianga/${artifact}`"
    :title="`${artifact} on Maven Central`"
    target="_blank"
    rel="noopener"
  >
    <img
      :src="`https://img.shields.io/maven-central/v/com.alianga/${artifact}?style=flat-square`"
      :alt="`${artifact} Maven Central`"
    />
  </a>
</template>

<style scoped>
.maven-badge {
  display: inline-flex;
  line-height: 0;
  text-decoration: none !important;
  border: 0;
  margin: 0 8px 8px 0;
}

.maven-badge img {
  height: 20px;
}
</style>
