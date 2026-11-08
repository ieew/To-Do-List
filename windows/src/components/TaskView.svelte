<script lang="ts">
  import ProgressCard from './ProgressCard.svelte';
  import TaskList from './TaskList.svelte';
</script>

<div class="main-scroll">
  <ProgressCard />
  <TaskList />
</div>

<style>
  .main-scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4) var(--space-5) var(--space-6);
  }
</style>
