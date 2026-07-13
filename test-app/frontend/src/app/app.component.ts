import { Component, OnInit } from '@angular/core';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
  standalone: false,
})
export class AppComponent implements OnInit {
  // sessionStorage (not localStorage) so the banner is only dismissed for the
  // current browser session — a new browser/tab shows it again.
  private static readonly DEMO_BANNER_DISMISSED_KEY = 'epistola-demo-banner-dismissed';

  showDemoBanner = true;

  constructor() {}

  ngOnInit() {
    this.showDemoBanner = sessionStorage.getItem(AppComponent.DEMO_BANNER_DISMISSED_KEY) !== 'true';
  }

  dismissDemoBanner(): void {
    this.showDemoBanner = false;
    sessionStorage.setItem(AppComponent.DEMO_BANNER_DISMISSED_KEY, 'true');
  }
}
