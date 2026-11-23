import { Greeting } from "@/components/greeting";
import { SummaryView } from "@/components/summary-view";

export default function Home() {
  return (
    <div className="space-y-8">
      <Greeting />
      <SummaryView />
    </div>
  );
}
